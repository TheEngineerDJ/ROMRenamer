package com.romrenamer.app.core.hash

/** Hash algorithms this app can compute and that No-Intro / Redump DATs publish. */
enum class HashAlgorithm(val hexLength: Int) {
    CRC32(8),
    MD5(32),
    SHA1(40),
}

/** The hashes computed for one file. A field is `null` when it was not requested. */
data class FileHashes(
    val crc32: String? = null,
    val md5: String? = null,
    val sha1: String? = null,
    /** Number of bytes that were actually hashed. */
    val bytesHashed: Long = 0L,
) {
    operator fun get(algorithm: HashAlgorithm): String? = when (algorithm) {
        HashAlgorithm.CRC32 -> crc32
        HashAlgorithm.MD5 -> md5
        HashAlgorithm.SHA1 -> sha1
    }

    /** Algorithms that have already been computed, so callers can avoid re-reading a file. */
    fun computed(): Set<HashAlgorithm> =
        HashAlgorithm.entries.filterTo(mutableSetOf()) { get(it) != null }

    fun merge(other: FileHashes): FileHashes = FileHashes(
        crc32 = crc32 ?: other.crc32,
        md5 = md5 ?: other.md5,
        sha1 = sha1 ?: other.sha1,
        bytesHashed = maxOf(bytesHashed, other.bytesHashed),
    )
}

object Hashes {

    /**
     * Puts a hash string from a DAT file into the same shape [HashEngine] produces:
     * lower-case hex, zero-padded to the algorithm's width.
     *
     * DAT publishers are inconsistent — No-Intro writes CRCs in upper case, and some
     * tools drop leading zeros ("b19ed48" for 0x0B19ED48). Both would silently fail to
     * match a computed hash without this step. Returns `null` for blank values or
     * anything that is not pure hex, so junk never enters the index.
     */
    fun normalize(value: String?, algorithm: HashAlgorithm): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.length > algorithm.hexLength) return null
        if (!trimmed.all { it.isHexDigit() }) return null
        return trimmed.lowercase().padStart(algorithm.hexLength, '0')
    }

    fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    fun crc32ToHex(value: Long): String =
        (value and 0xFFFFFFFFL).toString(16).padStart(HashAlgorithm.CRC32.hexLength, '0')

    private val HEX = "0123456789abcdef".toCharArray()

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
