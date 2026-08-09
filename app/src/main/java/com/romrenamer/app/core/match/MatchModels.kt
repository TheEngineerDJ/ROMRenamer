package com.romrenamer.app.core.match

import com.romrenamer.app.core.dat.DatEntry
import com.romrenamer.app.core.hash.FileHashes
import com.romrenamer.app.core.hash.HashAlgorithm
import com.romrenamer.app.core.storage.RomFile

/** Where a single scanned file ended up against the loaded DATs. */
sealed interface MatchStatus {

    /** Discovered, not hashed yet. */
    data object Pending : MatchStatus

    /** Currently being read and hashed. */
    data object Hashing : MatchStatus

    /** Exactly one release in the DAT owns these bytes. Cryptographically certain. */
    data class Matched(
        val entry: DatEntry,
        val via: HashAlgorithm,
        val fromArchive: Boolean = false,
    ) : MatchStatus

    /**
     * Matched on file name alone, because no hash in the database fits these bytes.
     *
     * This is a guess, not a verification: the file is a scrubbed or re-encoded rip, or a
     * different dump entirely, and only its name suggests what it is. Rows in this state
     * are flagged in the UI and start unticked.
     */
    data class FuzzyMatched(
        val entry: DatEntry,
        /** 0..1 similarity between the cleaned-up file name and the official title. */
        val confidence: Float,
        /** The cleaned-up file name that produced the match, shown to explain the guess. */
        val query: String,
        /** `true` when the file was skipped before hashing because no DAT size fit it. */
        val sizeMismatch: Boolean = false,
    ) : MatchStatus {
        val confidencePercent: Int get() = (confidence * 100).toInt()
    }

    /** Several different releases share these bytes; the user has to pick. */
    data class Ambiguous(val candidates: List<DatEntry>) : MatchStatus

    /** Several titles fit the file name equally well; renaming would be a coin flip. */
    data class FuzzyAmbiguous(
        val candidates: List<DatEntry>,
        val confidence: Float,
    ) : MatchStatus

    /** Hashed cleanly, but nothing in the loaded DATs has that hash. */
    data object Unmatched : MatchStatus

    /** Skipped without hashing because no DAT entry has this file's size. */
    data object SizeExcluded : MatchStatus

    /** Could not be read. */
    data class Failed(val message: String) : MatchStatus
}

/**
 * A file on disk plus everything the scan learned about it.
 *
 * Immutable so Compose can diff rows cheaply; the scanner replaces entries in the list as
 * results arrive.
 */
data class ScannedRom(
    val file: RomFile,
    val status: MatchStatus = MatchStatus.Pending,
    val hashes: FileHashes = FileHashes(),
    /** Set when the hashes came from inside a zip rather than the file itself. */
    val archiveEntryName: String? = null,
    /** Official name this file should be renamed to, or `null` when there is nothing to do. */
    val targetName: String? = null,
    val selected: Boolean = true,
) {
    /** Stable identity for list keys — a document URI does not change while the app runs. */
    val id: String get() = file.uri.toString()

    val matchedEntry: DatEntry?
        get() = when (val current = status) {
            is MatchStatus.Matched -> current.entry
            is MatchStatus.FuzzyMatched -> current.entry
            else -> null
        }

    /** `true` when the name came from a text match rather than a verified hash. */
    val isTextMatch: Boolean get() = status is MatchStatus.FuzzyMatched

    /** `true` when this row would actually change the file name. */
    val needsRename: Boolean
        get() = targetName != null && targetName != file.name

    /** `true` when the file is already correctly named. */
    val isAlreadyCorrect: Boolean
        get() = matchedEntry != null && targetName == file.name
}

/** Aggregate counts driving the summary bar and the confirm button's enabled state. */
data class ScanSummary(
    val total: Int = 0,
    val hashed: Int = 0,
    /** Verified by hash. */
    val matched: Int = 0,
    /** Matched by file name only, and therefore unverified. */
    val textMatched: Int = 0,
    val alreadyCorrect: Int = 0,
    val needsRename: Int = 0,
    /** Selected rows that rest on a text match rather than a hash. */
    val selectedTextMatches: Int = 0,
    val ambiguous: Int = 0,
    val unmatched: Int = 0,
    val failed: Int = 0,
    val bytesHashed: Long = 0L,
) {
    companion object {
        fun of(roms: List<ScannedRom>): ScanSummary = ScanSummary(
            total = roms.size,
            hashed = roms.count { it.hashes.crc32 != null || it.hashes.md5 != null || it.hashes.sha1 != null },
            matched = roms.count { it.status is MatchStatus.Matched },
            textMatched = roms.count { it.status is MatchStatus.FuzzyMatched },
            alreadyCorrect = roms.count { it.isAlreadyCorrect },
            needsRename = roms.count { it.needsRename },
            selectedTextMatches = roms.count { it.selected && it.needsRename && it.isTextMatch },
            ambiguous = roms.count {
                it.status is MatchStatus.Ambiguous || it.status is MatchStatus.FuzzyAmbiguous
            },
            unmatched = roms.count { it.status is MatchStatus.Unmatched || it.status is MatchStatus.SizeExcluded },
            failed = roms.count { it.status is MatchStatus.Failed },
            bytesHashed = roms.sumOf { it.hashes.bytesHashed },
        )
    }
}
