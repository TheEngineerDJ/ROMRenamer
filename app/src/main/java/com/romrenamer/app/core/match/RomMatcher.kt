package com.romrenamer.app.core.match

import com.romrenamer.app.core.dat.DatEntry
import com.romrenamer.app.core.dat.DatIndex
import com.romrenamer.app.core.dat.DatRom
import com.romrenamer.app.core.hash.FileHashes
import com.romrenamer.app.core.hash.HashAlgorithm

/** Outcome of testing one set of file hashes against the index. */
sealed interface MatchResult {

    data class Found(val entry: DatEntry, val via: HashAlgorithm) : MatchResult

    /**
     * The hashes computed so far cannot separate these candidates. Computing
     * [algorithms] would, so the scanner re-reads the file rather than giving up.
     */
    data class NeedsStrongerHash(
        val algorithms: Set<HashAlgorithm>,
        val candidates: List<DatEntry>,
    ) : MatchResult

    /** Genuinely ambiguous: distinct releases share these exact bytes. */
    data class Ambiguous(val candidates: List<DatEntry>) : MatchResult

    data object NotFound : MatchResult
}

/**
 * Matches file hashes against a [DatIndex].
 *
 * Lookup happens once, against the index's [DatIndex.primaryAlgorithm] — normally CRC32,
 * because it is the cheapest to compute and every DAT publishes it. That produces a short
 * candidate list, and any stronger hash the caller has already computed is then checked
 * directly against those candidates. This is what lets the index carry a single hash map
 * however many DATs are merged into it.
 *
 * CRC32 is only 32 bits, so a single hit is not always decisive: collisions exist and DATs
 * legitimately list the same bytes under several releases. When the candidates disagree,
 * the matcher reports which stronger hashes would separate them so the file can be re-read
 * once, for just those algorithms.
 */
class RomMatcher(private val index: DatIndex) {

    /** The cheapest algorithm set worth computing on the first pass over a file. */
    fun initialAlgorithms(): Set<HashAlgorithm> =
        setOf(index.primaryAlgorithm ?: HashAlgorithm.CRC32)

    fun match(hashes: FileHashes): MatchResult {
        val primary = index.primaryAlgorithm ?: return MatchResult.NotFound
        val primaryHash = hashes[primary] ?: return MatchResult.NotFound

        val candidates = index.find(primary, primaryHash)
        if (candidates.isEmpty()) return MatchResult.NotFound

        // A stronger hash that contradicts a candidate rules it out; one the DAT does not
        // publish for that candidate cannot say anything either way.
        val narrowed = candidates.filter { entry ->
            CONFIRMING.all { algorithm ->
                val fileHash = hashes[algorithm]
                val datHash = entry.rom[algorithm]
                fileHash == null || datHash == null || fileHash == datHash
            }
        }
        if (narrowed.isEmpty()) return MatchResult.NotFound

        // Several DAT entries can describe the same bytes — a game listed in both a parent
        // and a clone set, or the same dump present in two merged DATs. If they all agree
        // on the file name there is nothing for the user to decide.
        val distinct = narrowed.distinctBy { it.officialFileName }
        if (distinct.size == 1) {
            val entry = distinct.first()
            return MatchResult.Found(entry, confirmedBy(entry, hashes, primary))
        }

        val separating = separatingAlgorithms(narrowed, hashes)
        return if (separating.isNotEmpty()) {
            MatchResult.NeedsStrongerHash(separating, narrowed)
        } else {
            MatchResult.Ambiguous(distinct)
        }
    }

    /** The strongest algorithm on which the file and the matched entry actually agree. */
    private fun confirmedBy(
        entry: DatEntry,
        hashes: FileHashes,
        fallback: HashAlgorithm,
    ): HashAlgorithm = STRENGTH_ORDER.firstOrNull { algorithm ->
        val fileHash = hashes[algorithm]
        fileHash != null && fileHash == entry.rom[algorithm]
    } ?: fallback

    /**
     * Algorithms worth re-reading the file for: not yet computed, published for every
     * candidate, and actually differing between them. An algorithm whose values are
     * identical across the candidates could never break the tie.
     */
    private fun separatingAlgorithms(
        candidates: List<DatEntry>,
        hashes: FileHashes,
    ): Set<HashAlgorithm> = CONFIRMING.filterTo(mutableSetOf()) { algorithm ->
        hashes[algorithm] == null &&
            candidates.all { it.rom[algorithm] != null } &&
            candidates.mapTo(mutableSetOf()) { it.rom[algorithm] }.size > 1
    }

    private operator fun DatRom.get(algorithm: HashAlgorithm): String? = when (algorithm) {
        HashAlgorithm.CRC32 -> crc32
        HashAlgorithm.MD5 -> md5
        HashAlgorithm.SHA1 -> sha1
    }

    private companion object {
        /** Strongest first. */
        val STRENGTH_ORDER = listOf(HashAlgorithm.SHA1, HashAlgorithm.MD5, HashAlgorithm.CRC32)

        /** Hashes strong enough to confirm or rule out a candidate found by CRC32. */
        val CONFIRMING = listOf(HashAlgorithm.SHA1, HashAlgorithm.MD5)
    }
}
