package com.romrenamer.app.core.match

import com.romrenamer.app.core.dat.DatGame
import com.romrenamer.app.core.dat.DatIndexBuilder
import com.romrenamer.app.core.dat.DatRom
import com.romrenamer.app.core.hash.FileHashes
import com.romrenamer.app.core.hash.HashAlgorithm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RomMatcherTest {

    @Test
    fun `a unique CRC32 is enough to match`() {
        val matcher = RomMatcher(indexOf(game("Super Mario World (USA)", crc = "b19ed489")))

        val result = matcher.match(FileHashes(crc32 = "b19ed489"))

        assertTrue(result is MatchResult.Found)
        result as MatchResult.Found
        assertEquals("Super Mario World (USA)", result.entry.game.name)
        assertEquals(HashAlgorithm.CRC32, result.via)
    }

    @Test
    fun `an unknown hash does not match`() {
        val matcher = RomMatcher(indexOf(game("Anything", crc = "b19ed489")))

        assertEquals(MatchResult.NotFound, matcher.match(FileHashes(crc32 = "ffffffff")))
    }

    @Test
    fun `a strong hash miss is conclusive and skips the CRC lookup`() {
        val matcher = RomMatcher(
            indexOf(game("Collision Bait", crc = "aaaaaaaa", sha1 = "a".repeat(40))),
        )

        // The CRC32 is in the DAT, but the SHA-1 proves these are different bytes.
        val result = matcher.match(FileHashes(crc32 = "aaaaaaaa", sha1 = "b".repeat(40)))

        assertEquals(MatchResult.NotFound, result)
    }

    @Test
    fun `duplicate entries with the same file name are not ambiguous`() {
        // The same dump listed under a parent and its clone: two entries, one file name.
        val matcher = RomMatcher(
            indexOf(
                game("Game (USA)", crc = "12345678", romName = "Game (USA).bin"),
                game("Game (USA) (Rev 1)", crc = "12345678", romName = "Game (USA).bin"),
            ),
        )

        val result = matcher.match(FileHashes(crc32 = "12345678"))

        assertTrue(result is MatchResult.Found)
        assertEquals("Game (USA).bin", (result as MatchResult.Found).entry.officialFileName)
    }

    @Test
    fun `a colliding CRC32 asks for a stronger hash`() {
        val matcher = RomMatcher(
            indexOf(
                game("Game A (USA)", crc = "12345678", romName = "Game A (USA).bin", sha1 = "a".repeat(40)),
                game("Game B (Japan)", crc = "12345678", romName = "Game B (Japan).bin", sha1 = "b".repeat(40)),
            ),
        )

        val result = matcher.match(FileHashes(crc32 = "12345678"))

        assertTrue(result is MatchResult.NeedsStrongerHash)
        result as MatchResult.NeedsStrongerHash
        assertTrue(HashAlgorithm.SHA1 in result.algorithms)
        assertEquals(2, result.candidates.size)
    }

    @Test
    fun `the stronger hash resolves the collision on the second pass`() {
        val matcher = RomMatcher(
            indexOf(
                game("Game A (USA)", crc = "12345678", romName = "Game A (USA).bin", sha1 = "a".repeat(40)),
                game("Game B (Japan)", crc = "12345678", romName = "Game B (Japan).bin", sha1 = "b".repeat(40)),
            ),
        )

        val result = matcher.match(FileHashes(crc32 = "12345678", sha1 = "b".repeat(40)))

        assertTrue(result is MatchResult.Found)
        result as MatchResult.Found
        assertEquals("Game B (Japan)", result.entry.game.name)
        assertEquals(HashAlgorithm.SHA1, result.via)
    }

    @Test
    fun `identical bytes under two names stay ambiguous instead of looping`() {
        // Same SHA-1 on both entries: re-reading the file could never separate them.
        val matcher = RomMatcher(
            indexOf(
                game("Game (USA)", crc = "12345678", romName = "Game (USA).bin", sha1 = "c".repeat(40)),
                game("Game (Europe)", crc = "12345678", romName = "Game (Europe).bin", sha1 = "c".repeat(40)),
            ),
        )

        val result = matcher.match(FileHashes(crc32 = "12345678"))

        assertTrue(result is MatchResult.Ambiguous)
        assertEquals(2, (result as MatchResult.Ambiguous).candidates.size)
    }

    @Test
    fun `initial algorithms follow what the DAT publishes`() {
        val crcOnly = RomMatcher(indexOf(game("Only CRC", crc = "12345678")))
        assertEquals(setOf(HashAlgorithm.CRC32), crcOnly.initialAlgorithms())

        val sha1Only = RomMatcher(
            indexOf(game("Only SHA1", crc = null, sha1 = "d".repeat(40))),
        )
        assertEquals(setOf(HashAlgorithm.SHA1), sha1Only.initialAlgorithms())
    }

    private fun game(
        name: String,
        crc: String? = null,
        md5: String? = null,
        sha1: String? = null,
        romName: String = "$name.bin",
        size: Long = 1024L,
    ) = DatGame(
        name = name,
        roms = listOf(DatRom(name = romName, size = size, crc32 = crc, md5 = md5, sha1 = sha1)),
    )

    private fun indexOf(vararg games: DatGame) = DatIndexBuilder()
        .apply { games.forEach { addGame(it, source = "test") } }
        .build()
}
