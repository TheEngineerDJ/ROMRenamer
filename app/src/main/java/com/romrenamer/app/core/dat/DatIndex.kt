package com.romrenamer.app.core.dat

import com.romrenamer.app.core.hash.HashAlgorithm

/**
 * A searchable, immutable view over one or more parsed DAT files.
 *
 * The index is hash-first: given the CRC32 (or MD5/SHA1) of a local file it returns every
 * DAT entry with that hash, in O(1). Several DATs merge into a single index, so a mixed
 * folder can be matched against every system the user owns at once.
 *
 * Only one hash map is built — see [primaryAlgorithm]. Merging a large DAT collection
 * produces hundreds of thousands of entries, and a map per algorithm would triple the
 * memory for no benefit: once a CRC32 lookup has produced a short candidate list, a
 * stronger hash is checked directly against those candidates rather than through a map.
 */
class DatIndex internal constructor(
    val headers: List<DatHeader>,
    val games: List<DatGame>,
    private val index: Map<String, List<DatEntry>>,
    /** The algorithm [index] is keyed by, or `null` when nothing usable was loaded. */
    val primaryAlgorithm: HashAlgorithm?,
    private val sizes: Set<Long>,
    /** `false` when at least one entry had no `size` attribute, disabling the size filter. */
    private val sizeIndexComplete: Boolean,
    /** Algorithms the source DATs publish, whether or not they are indexed. */
    val availableAlgorithms: Set<HashAlgorithm>,
    val romCount: Int,
) {

    val gameCount: Int get() = games.size

    val isEmpty: Boolean get() = games.isEmpty() || primaryAlgorithm == null

    /** Number of distinct DAT files merged into this index. */
    val sourceCount: Int get() = headers.size

    /** Human-readable summary of the loaded DATs, e.g. for a status line. */
    val displayName: String
        get() = when (headers.size) {
            0 -> "No DAT loaded"
            1 -> headers[0].displayName
            else -> "${headers.size} DATs"
        }

    /**
     * Looks up entries by hash.
     *
     * Only [primaryAlgorithm] is indexed; any other algorithm returns empty even when the
     * DATs publish it. Use [DatRom] fields directly to confirm a candidate.
     */
    fun find(algorithm: HashAlgorithm, hash: String?): List<DatEntry> {
        if (algorithm != primaryAlgorithm) return emptyList()
        val key = hash?.lowercase() ?: return emptyList()
        return index[key].orEmpty()
    }

    fun findByCrc32(crc32: String?): List<DatEntry> = find(HashAlgorithm.CRC32, crc32)

    fun findByMd5(md5: String?): List<DatEntry> = find(HashAlgorithm.MD5, md5)

    fun findBySha1(sha1: String?): List<DatEntry> = find(HashAlgorithm.SHA1, sha1)

    /**
     * `true` if any DAT entry has exactly this byte size.
     *
     * Two files can only have the same hash if they have the same length, so a size miss is
     * a guaranteed hash miss — the scanner uses this to skip reading multi-gigabyte discs
     * that cannot possibly be in the loaded DATs. Always `true` when the size index is
     * incomplete, so an under-specified DAT degrades to "hash everything" rather than to
     * false negatives.
     */
    fun hasSize(size: Long): Boolean = !sizeIndexComplete || size in sizes

    companion object {
        val EMPTY: DatIndex = DatIndexBuilder().build()
    }
}

/**
 * Accumulates parsed DAT files into a single [DatIndex]. Not thread-safe.
 *
 * CRC32 is indexed as entries arrive, since practically every DAT publishes it. In the rare
 * case that none did, [build] falls back to indexing MD5 or SHA-1 in a second pass over the
 * games collected so far.
 */
class DatIndexBuilder {

    private val headers = mutableListOf<DatHeader>()
    private val games = mutableListOf<DatGame>()
    private val byCrc32 = HashMap<String, MutableList<DatEntry>>()
    private val sizes = HashSet<Long>()
    private val available = mutableSetOf<HashAlgorithm>()
    private val sources = mutableListOf<String>()
    private var sizeIndexComplete = true
    private var romCount = 0

    /** Entries indexed so far, so a caller can stop before a merge grows unmanageable. */
    val indexedRomCount: Int get() = romCount

    val gameCount: Int get() = games.size

    fun addHeader(header: DatHeader) = apply { headers += header }

    fun addGame(game: DatGame, source: String) = apply {
        games += game
        sources += source
        for (rom in game.roms) {
            // Entries flagged nodump/baddump carry placeholder hashes; indexing them would
            // point real files at the wrong release.
            if (rom.isDumpKnownBad) continue
            romCount++
            rom.crc32?.let { crc ->
                available += HashAlgorithm.CRC32
                byCrc32.getOrPut(crc) { mutableListOf() } += DatEntry(game, rom, source)
            }
            if (rom.md5 != null) available += HashAlgorithm.MD5
            if (rom.sha1 != null) available += HashAlgorithm.SHA1
            val size = rom.size
            if (size != null && size >= 0) sizes += size else sizeIndexComplete = false
        }
    }

    fun build(): DatIndex {
        val primary = when {
            byCrc32.isNotEmpty() -> HashAlgorithm.CRC32
            HashAlgorithm.SHA1 in available -> HashAlgorithm.SHA1
            HashAlgorithm.MD5 in available -> HashAlgorithm.MD5
            else -> null
        }

        val index: Map<String, List<DatEntry>> = when (primary) {
            HashAlgorithm.CRC32 -> byCrc32.mapValues { it.value.toList() }
            null -> emptyMap()
            else -> buildFallbackIndex(primary)
        }

        return DatIndex(
            headers = headers.toList(),
            games = games.toList(),
            index = index,
            primaryAlgorithm = primary,
            sizes = sizes.toSet(),
            sizeIndexComplete = sizeIndexComplete && games.isNotEmpty(),
            availableAlgorithms = available.toSet(),
            romCount = romCount,
        )
    }

    /** Indexes by a stronger hash for the rare DAT that omits CRC32 entirely. */
    private fun buildFallbackIndex(algorithm: HashAlgorithm): Map<String, List<DatEntry>> {
        val map = HashMap<String, MutableList<DatEntry>>()
        games.forEachIndexed { position, game ->
            val source = sources[position]
            for (rom in game.roms) {
                if (rom.isDumpKnownBad) continue
                val hash = when (algorithm) {
                    HashAlgorithm.SHA1 -> rom.sha1
                    HashAlgorithm.MD5 -> rom.md5
                    HashAlgorithm.CRC32 -> rom.crc32
                } ?: continue
                map.getOrPut(hash) { mutableListOf() } += DatEntry(game, rom, source)
            }
        }
        return map.mapValues { it.value.toList() }
    }
}
