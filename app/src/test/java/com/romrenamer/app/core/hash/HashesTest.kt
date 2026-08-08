package com.romrenamer.app.core.hash

import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HashesTest {

    @Test
    fun `normalises DAT hashes to lower case`() {
        assertEquals("b19ed489", Hashes.normalize("B19ED489", HashAlgorithm.CRC32))
    }

    @Test
    fun `restores leading zeros some DAT writers drop`() {
        assertEquals("0000abcd", Hashes.normalize("abcd", HashAlgorithm.CRC32))
        assertEquals("0".repeat(31) + "1", Hashes.normalize("1", HashAlgorithm.MD5))
    }

    @Test
    fun `rejects values that are not hashes`() {
        assertNull(Hashes.normalize(null, HashAlgorithm.CRC32))
        assertNull(Hashes.normalize("", HashAlgorithm.CRC32))
        assertNull(Hashes.normalize("   ", HashAlgorithm.CRC32))
        assertNull(Hashes.normalize("not-hex!", HashAlgorithm.CRC32))
        // Too long for the algorithm: padding it would silently corrupt the index.
        assertNull(Hashes.normalize("b19ed489ff", HashAlgorithm.CRC32))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("b19ed489", Hashes.normalize("  B19ED489\n", HashAlgorithm.CRC32))
    }

    @Test
    fun `formats a CRC32 the same way DATs do`() {
        val crc = CRC32().apply { update("The quick brown fox".toByteArray()) }

        val hex = Hashes.crc32ToHex(crc.value)

        assertEquals(8, hex.length)
        assertEquals(crc.value, hex.toLong(16))
    }

    @Test
    fun `pads small CRC32 values to eight digits`() {
        assertEquals("00000001", Hashes.crc32ToHex(1L))
        assertEquals("ffffffff", Hashes.crc32ToHex(0xFFFFFFFFL))
    }

    @Test
    fun `converts digest bytes to lower-case hex`() {
        val bytes = byteArrayOf(0x00, 0x0F, 0x7F, 0xFF.toByte(), 0xA0.toByte())

        assertEquals("000f7fffa0", Hashes.toHex(bytes))
    }

    @Test
    fun `reports which algorithms have been computed`() {
        val hashes = FileHashes(crc32 = "12345678", sha1 = "a".repeat(40))

        assertEquals(setOf(HashAlgorithm.CRC32, HashAlgorithm.SHA1), hashes.computed())
        assertEquals("12345678", hashes[HashAlgorithm.CRC32])
        assertNull(hashes[HashAlgorithm.MD5])
    }

    @Test
    fun `merging keeps existing values and fills the gaps`() {
        val first = FileHashes(crc32 = "12345678", bytesHashed = 1024L)
        val second = FileHashes(crc32 = "ffffffff", md5 = "b".repeat(32), bytesHashed = 512L)

        val merged = first.merge(second)

        assertEquals("12345678", merged.crc32)
        assertEquals("b".repeat(32), merged.md5)
        assertTrue(merged.bytesHashed == 1024L)
    }
}
