package com.romrenamer.app.core.match

import com.romrenamer.app.core.dat.DatEntry
import com.romrenamer.app.core.dat.DatIndex
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
 * The scan hashes with CRC32 first because it is the cheapest and every DAT publishes it.
 * CRC32 is only 32 bits, though, so collisions are possible and DATs legitimately list the
 * same bytes under several releases — when a CRC lookup is not decisive the matcher asks
 * for MD5/SHA-1 and the file is re-read. In practice that second pass is rare.
 */
class RomMatcher(private val index: DatIndex) {

    /** The cheapest algorithm set worth computing on the first pass over a file. */
    fun initialAlgorithms(): Set<HashAlgorithm> = when {
        HashAlgorithm.CRC32 in index.availableAlgorithms -> setOf(HashAlgorithm.CRC32)
        HashAlgorithm.MD5 in index.availableAlgorithms -> setOf(HashAlgorithm.MD5)
        HashAlgorithm.SHA1 in index.availableAlgorithms -> setOf(HashAlgorithm.SHA1)
        else -> setOf(HashAlgorithm.CRC32)
    }

    fun match(hashes: FileHashes): MatchResult {
        // Strongest first: a SHA-1 hit needs no corroboration, a CRC32 hit might.
        for (algorithm in STRENGTH_ORDER) {
            val hash = hashes[algorithm] ?: continue
            val candidates = index.find(algorithm, hash)
            if (candidates.isEmpty()) {
                // A miss on a strong hash is conclusive — weaker hashes cannot rescue it.
                if (algorithm != HashAlgorithm.CRC32) return MatchResult.NotFound
                continue
            }
            return resolve(candidates, algorithm, hashes)
        }
        return MatchResult.NotFound
    }

    private fun resolve(
        candidates: List<DatEntry>,
        via: HashAlgorithm,
        hashes: FileHashes,
    ): MatchResult {
        if (candidates.size == 1) return MatchResult.Found(candidates.first(), via)

        // Several DAT entries can describe the same bytes — a game listed in both a parent
        // and a clone set, or the same dump in two merged DATs. If they all agree on the
        // file name there is nothing for the user to decide.
        val distinctNames = candidates.distinctBy { it.officialFileName }
        if (distinctNames.size == 1) return MatchResult.Found(distinctNames.first(), via)

        val stronger = strongerAlgorithmsFor(candidates, hashes)
        if (stronger.isNotEmpty()) {
            return MatchResult.NeedsStrongerHash(stronger, candidates)
        }
        return MatchResult.Ambiguous(distinctNames)
    }

    /**
     * Algorithms that are both published for every candidate and not yet computed — the
     * only ones that could actually break the tie.
     */
    private fun strongerAlgorithmsFor(
        candidates: List<DatEntry>,
        hashes: FileHashes,
    ): Set<HashAlgorithm> {
        val computed = hashes.computed()
        val usable = mutableSetOf<HashAlgorithm>()
        if (HashAlgorithm.SHA1 !in computed && candidates.all { it.rom.sha1 != null }) {
            usable += HashAlgorithm.SHA1
        }
        if (HashAlgorithm.MD5 !in computed && candidates.all { it.rom.md5 != null }) {
            usable += HashAlgorithm.MD5
        }
        // Distinct candidates whose stronger hashes are all identical are the same dump
        // under different names; re-reading the file would not help.
        if (usable.size == 1) {
            val only = usable.first()
            val values = candidates.mapNotNull { it.rom[only] }.toSet()
            if (values.size <= 1) return emptySet()
        }
        return usable
    }

    private operator fun com.romrenamer.app.core.dat.DatRom.get(algorithm: HashAlgorithm): String? =
        when (algorithm) {
            HashAlgorithm.CRC32 -> crc32
            HashAlgorithm.MD5 -> md5
            HashAlgorithm.SHA1 -> sha1
        }

    private companion object {
        val STRENGTH_ORDER = listOf(HashAlgorithm.SHA1, HashAlgorithm.MD5, HashAlgorithm.CRC32)
    }
}
