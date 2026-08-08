package com.romrenamer.app.core.dat

import com.romrenamer.app.core.hash.HashAlgorithm

/**
 * A searchable, immutable view over one or more parsed DAT files.
 *
 * The index is hash-first: given the CRC32 (or MD5/SHA1) of a local file it returns every
 * DAT entry with that hash, in O(1). Several DATs can be merged into a single index so a
 * mixed folder can be matched against, say, the SNES and Mega Drive collections at once.
 */
class DatIndex internal constructor(
    val headers: List<DatHeader>,
    val games: List<DatGame>,
    private val byCrc32: Map<String, List<DatEntry>>,
    private val byMd5: Map<String, List<DatEntry>>,
    private val bySha1: Map<String, List<DatEntry>>,
    private val sizes: Set<Long>,
    /** `false` when at least one entry had no `size` attribute, disabling the size filter. */
    private val sizeIndexComplete: Boolean,
    /** Algorithms that are actually populated in the source DATs. */
    val availableAlgorithms: Set<HashAlgorithm>,
) {

    val gameCount: Int get() = games.size

    val romCount: Int get() = games.sumOf { it.roms.size }

    val isEmpty: Boolean get() = games.isEmpty()

    /** Human-readable summary of the loaded DATs, e.g. for a status line. */
    val displayName: String
        get() = when (headers.size) {
            0 -> "No DAT loaded"
            1 -> headers[0].displayName
            else -> "${headers.size} DATs (${headers.first().displayName}, …)"
        }

    fun find(algorithm: HashAlgorithm, hash: String?): List<DatEntry> {
        val key = hash?.lowercase() ?: return emptyList()
        return when (algorithm) {
            HashAlgorithm.CRC32 -> byCrc32
            HashAlgorithm.MD5 -> byMd5
            HashAlgorithm.SHA1 -> bySha1
        }[key].orEmpty()
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

/** Accumulates parsed DAT files into a single [DatIndex]. Not thread-safe. */
class DatIndexBuilder {

    private val headers = mutableListOf<DatHeader>()
    private val games = mutableListOf<DatGame>()
    private val byCrc32 = HashMap<String, MutableList<DatEntry>>()
    private val byMd5 = HashMap<String, MutableList<DatEntry>>()
    private val bySha1 = HashMap<String, MutableList<DatEntry>>()
    private val sizes = HashSet<Long>()
    private val available = mutableSetOf<HashAlgorithm>()
    private var sizeIndexComplete = true

    fun addHeader(header: DatHeader) = apply { headers += header }

    fun addGame(game: DatGame, source: String) = apply {
        games += game
        for (rom in game.roms) {
            // Entries flagged nodump/baddump carry placeholder hashes; indexing them would
            // point real files at the wrong release.
            if (rom.isDumpKnownBad) continue
            val entry = DatEntry(game = game, rom = rom, source = source)
            index(byCrc32, rom.crc32, entry, HashAlgorithm.CRC32)
            index(byMd5, rom.md5, entry, HashAlgorithm.MD5)
            index(bySha1, rom.sha1, entry, HashAlgorithm.SHA1)
            val size = rom.size
            if (size != null && size >= 0) sizes += size else sizeIndexComplete = false
        }
    }

    fun build(): DatIndex = DatIndex(
        headers = headers.toList(),
        games = games.toList(),
        byCrc32 = byCrc32.mapValues { it.value.toList() },
        byMd5 = byMd5.mapValues { it.value.toList() },
        bySha1 = bySha1.mapValues { it.value.toList() },
        sizes = sizes.toSet(),
        sizeIndexComplete = sizeIndexComplete && games.isNotEmpty(),
        availableAlgorithms = available.toSet(),
    )

    private fun index(
        map: HashMap<String, MutableList<DatEntry>>,
        hash: String?,
        entry: DatEntry,
        algorithm: HashAlgorithm,
    ) {
        if (hash == null) return
        available += algorithm
        map.getOrPut(hash) { mutableListOf() } += entry
    }
}
