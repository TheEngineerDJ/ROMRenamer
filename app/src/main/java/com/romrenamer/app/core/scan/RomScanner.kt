package com.romrenamer.app.core.scan

import android.net.Uri
import com.romrenamer.app.core.dat.DatEntry
import com.romrenamer.app.core.dat.DatIndex
import com.romrenamer.app.core.hash.FileHashes
import com.romrenamer.app.core.hash.HashAlgorithm
import com.romrenamer.app.core.hash.HashEngine
import com.romrenamer.app.core.hash.HashOutcome
import com.romrenamer.app.core.hash.HashingException
import com.romrenamer.app.core.match.FuzzyResult
import com.romrenamer.app.core.match.FuzzyTitleMatcher
import com.romrenamer.app.core.match.MatchResult
import com.romrenamer.app.core.match.MatchStatus
import com.romrenamer.app.core.match.RomMatcher
import com.romrenamer.app.core.match.ScannedRom
import com.romrenamer.app.core.rename.NamingPolicy
import com.romrenamer.app.core.rename.RomNaming
import com.romrenamer.app.core.storage.RomFile
import com.romrenamer.app.core.storage.RomFileFilter
import com.romrenamer.app.core.storage.SafHandler
import com.romrenamer.app.core.storage.StorageAccessException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Tunables for a scan. */
data class ScanOptions(
    val filter: RomFileFilter = RomFileFilter(),
    val namingPolicy: NamingPolicy = NamingPolicy.DAT_ROM_NAME,
    /** Hash the ROM inside a `.zip` instead of the archive, which is what DATs catalogue. */
    val inspectArchives: Boolean = true,
    /** Skip reading files whose size appears in no DAT entry. */
    val useSizeFilter: Boolean = true,
    /**
     * Compute MD5 and SHA-1 alongside CRC32 on the first pass. Costs CPU but no extra I/O,
     * and removes the second read that ambiguous CRC hits would otherwise need.
     */
    val alwaysComputeStrongHashes: Boolean = false,
    /**
     * Fall back to matching the file name against DAT titles when the bytes match nothing.
     *
     * Scrubbed and re-encoded rips fail every hash check by design, so without this they
     * are simply unmatched. The result is a guess and is reported as one.
     */
    val fuzzyMatching: Boolean = true,

    /** Similarity a file name must reach to be accepted as a text match. */
    val fuzzyThreshold: Float = FuzzyTitleMatcher.DEFAULT_THRESHOLD,

    /**
     * Files hashed at once. Flash storage rewards a little parallelism, but too much just
     * thrashes the provider and starves the UI.
     */
    val concurrency: Int = 3,
)

/** Incremental scan progress. */
sealed interface ScanEvent {
    /** Emitted while the directory tree is still being walked. */
    data class Listing(val filesFound: Int, val currentPath: String) : ScanEvent

    /** The full file list is known; every entry starts as [MatchStatus.Pending]. */
    data class Discovered(val roms: List<ScannedRom>) : ScanEvent

    /** One file finished hashing and matching. */
    data class RomUpdated(val rom: ScannedRom, val completed: Int, val total: Int) : ScanEvent

    /** Byte-level progress for a single large file. */
    data class FileProgress(val romId: String, val bytesHashed: Long, val totalBytes: Long) : ScanEvent

    data object Finished : ScanEvent

    data class Failed(val message: String) : ScanEvent
}

/**
 * Drives a full scan: walk the granted folder, hash what it finds, and match each file
 * against the loaded DATs.
 *
 * Results stream out as they are produced rather than arriving in one batch at the end, so
 * a folder with thousands of ROMs fills the list progressively. Files are processed
 * concurrently up to [ScanOptions.concurrency]; cancelling the collecting coroutine stops
 * every worker.
 */
class RomScanner(
    private val safHandler: SafHandler,
    private val hashEngine: HashEngine,
) {

    fun scan(treeUri: Uri, index: DatIndex, options: ScanOptions = ScanOptions()): Flow<ScanEvent> =
        channelFlow {
            val matcher = RomMatcher(index)
            // Built on first use and shared by every worker. A library that hashes cleanly
            // never touches it, and never pays to tokenise the whole database.
            val fuzzy = lazy { FuzzyTitleMatcher.build(index, options.fuzzyThreshold) }

            val files = try {
                send(ScanEvent.Listing(0, ""))
                safHandler.listFilesRecursively(treeUri, options.filter) { found, path ->
                    trySend(ScanEvent.Listing(found, path))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: StorageAccessException) {
                send(ScanEvent.Failed(e.message ?: "Could not read that folder."))
                return@channelFlow
            } catch (e: Exception) {
                send(ScanEvent.Failed("Could not read that folder: ${e.message}"))
                return@channelFlow
            }

            send(ScanEvent.Discovered(files.map { ScannedRom(file = it) }))
            if (files.isEmpty()) {
                send(ScanEvent.Finished)
                return@channelFlow
            }

            val completed = AtomicInteger(0)
            val permits = Semaphore(options.concurrency.coerceIn(1, 8))

            coroutineScope {
                files.forEach { file ->
                    launch {
                        permits.withPermit {
                            val rom = process(file, index, matcher, fuzzy, options) { bytes ->
                                trySend(ScanEvent.FileProgress(file.uri.toString(), bytes, file.size))
                            }
                            send(
                                ScanEvent.RomUpdated(
                                    rom = rom,
                                    completed = completed.incrementAndGet(),
                                    total = files.size,
                                ),
                            )
                        }
                    }
                }
            }

            send(ScanEvent.Finished)
        }.buffer(capacity = 256, onBufferOverflow = BufferOverflow.SUSPEND)

    private suspend fun process(
        file: RomFile,
        index: DatIndex,
        matcher: RomMatcher,
        fuzzy: Lazy<FuzzyTitleMatcher>,
        options: ScanOptions,
        onBytes: (Long) -> Unit,
    ): ScannedRom {
        // An archive's on-disk size is the compressed size, which no DAT records, so the
        // size shortcut cannot be applied to it.
        val isArchive = options.inspectArchives && file.extension == "zip"
        if (options.useSizeFilter && !isArchive && !index.hasSize(file.size)) {
            // No DAT entry is this long, so hashing is pointless — but a scrubbed rip is
            // exactly the file that would land here, so its name still deserves a look.
            return fuzzyFallback(file, fuzzy, options, sizeMismatch = true)
                ?: ScannedRom(file = file, status = MatchStatus.SizeExcluded, selected = false)
        }

        val algorithms = buildSet {
            addAll(matcher.initialAlgorithms())
            if (options.alwaysComputeStrongHashes) {
                add(HashAlgorithm.MD5)
                add(HashAlgorithm.SHA1)
            }
        }

        var outcome: HashOutcome = try {
            hashEngine.hashRomFile(file, algorithms, options.inspectArchives, onBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HashingException) {
            return ScannedRom(file = file, status = MatchStatus.Failed(e.message.orEmpty()), selected = false)
        } catch (e: Exception) {
            return ScannedRom(
                file = file,
                status = MatchStatus.Failed(e.message ?: "Could not hash this file."),
                selected = false,
            )
        }

        var result = matcher.match(outcome.hashes)
        if (result is MatchResult.NeedsStrongerHash) {
            outcome = try {
                val extra = hashEngine.hashRomFile(file, result.algorithms, options.inspectArchives, onBytes)
                outcome.copy(hashes = outcome.hashes.merge(extra.hashes))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                outcome
            }
            result = matcher.match(outcome.hashes)
        }

        if (result is MatchResult.NotFound) {
            fuzzyFallback(file, fuzzy, options, sizeMismatch = false, hashes = outcome.hashes)
                ?.let { return it }
        }

        return toScannedRom(file, outcome, result, options)
    }

    /**
     * Tries to identify a file by name once its bytes have come up empty.
     *
     * Returns `null` when the fallback is switched off or nothing scores high enough, so the
     * caller can keep whatever unmatched status it already had.
     */
    private fun fuzzyFallback(
        file: RomFile,
        fuzzy: Lazy<FuzzyTitleMatcher>,
        options: ScanOptions,
        sizeMismatch: Boolean,
        hashes: FileHashes = FileHashes(),
    ): ScannedRom? {
        if (!options.fuzzyMatching) return null

        return when (val result = fuzzy.value.match(file.name, file.extension)) {
            is FuzzyResult.Matched -> ScannedRom(
                file = file,
                hashes = hashes,
                status = MatchStatus.FuzzyMatched(
                    entry = result.entry,
                    confidence = result.confidence,
                    query = result.query,
                    sizeMismatch = sizeMismatch,
                ),
                targetName = RomNaming.targetName(
                    entry = result.entry,
                    currentName = file.name,
                    policy = fuzzyNamingPolicy(result.entry, file, options),
                ),
                // Never ticked by default: the bytes were not verified, so applying the
                // rename has to be a decision the user makes.
                selected = false,
            )

            is FuzzyResult.Ambiguous -> ScannedRom(
                file = file,
                hashes = hashes,
                status = MatchStatus.FuzzyAmbiguous(result.candidates, result.confidence),
                selected = false,
            )

            FuzzyResult.NoMatch -> null
        }
    }

    /**
     * Text matches keep the extension already on disk.
     *
     * The official name is only a guess here, so rewriting a `.iso` to the DAT's `.cue`
     * would assert a change of format on no evidence at all. A hash match has earned the
     * right to do that; a name match has not.
     */
    private fun fuzzyNamingPolicy(
        entry: DatEntry,
        file: RomFile,
        options: ScanOptions,
    ): NamingPolicy {
        val datExtension = entry.officialFileName.substringAfterLast('.', "")
        return if (datExtension.equals(file.extension, ignoreCase = true)) {
            options.namingPolicy
        } else {
            NamingPolicy.GAME_TITLE_KEEP_EXTENSION
        }
    }

    private fun toScannedRom(
        file: RomFile,
        outcome: HashOutcome,
        result: MatchResult,
        options: ScanOptions,
    ): ScannedRom {
        val base = ScannedRom(
            file = file,
            hashes = outcome.hashes,
            archiveEntryName = outcome.archiveEntryName,
        )
        return when (result) {
            is MatchResult.Found -> {
                val target = RomNaming.targetName(
                    entry = result.entry,
                    file = file,
                    fromArchive = outcome.isFromArchive,
                    policy = options.namingPolicy,
                )
                base.copy(
                    status = MatchStatus.Matched(
                        entry = result.entry,
                        via = result.via,
                        fromArchive = outcome.isFromArchive,
                    ),
                    targetName = target,
                    selected = target != file.name,
                )
            }

            is MatchResult.Ambiguous ->
                base.copy(status = MatchStatus.Ambiguous(result.candidates), selected = false)

            // The scanner already re-hashed once; if the matcher still wants more, treat the
            // candidates as a choice for the user rather than looping.
            is MatchResult.NeedsStrongerHash ->
                base.copy(status = MatchStatus.Ambiguous(result.candidates), selected = false)

            MatchResult.NotFound -> base.copy(status = MatchStatus.Unmatched, selected = false)
        }
    }
}
