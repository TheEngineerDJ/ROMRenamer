package com.romrenamer.app.core.rename

import com.romrenamer.app.core.dat.DatEntry
import com.romrenamer.app.core.dat.DatGame
import com.romrenamer.app.core.dat.DatRom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RomNamingTest {

    @Test
    fun `uses the DAT file name verbatim by default`() {
        val target = RomNaming.targetName(entry("Super Mario World (USA).sfc"), "smw.smc")

        assertEquals("Super Mario World (USA).sfc", target)
    }

    @Test
    fun `keeps the on-disk extension when asked to`() {
        val target = RomNaming.targetName(
            entry = entry("Super Mario World (USA).sfc", gameName = "Super Mario World (USA)"),
            currentName = "smw.smc",
            policy = NamingPolicy.GAME_TITLE_KEEP_EXTENSION,
        )

        assertEquals("Super Mario World (USA).smc", target)
    }

    @Test
    fun `a ROM matched inside a zip keeps the zip extension`() {
        val target = RomNaming.targetName(
            entry = entry("Sonic The Hedgehog (USA, Europe).md"),
            currentName = "sonic1.zip",
            fromArchive = true,
        )

        assertEquals("Sonic The Hedgehog (USA, Europe).zip", target)
    }

    @Test
    fun `a file with no extension gets none invented for it`() {
        val target = RomNaming.targetName(
            entry = entry("Some Game (USA).bin", gameName = "Some Game (USA)"),
            currentName = "romfile",
            policy = NamingPolicy.GAME_TITLE_KEEP_EXTENSION,
        )

        assertEquals("Some Game (USA)", target)
    }

    @Test
    fun `strips characters that FAT32 rejects`() {
        assertEquals(
            "Game _ Sequel (USA) _v1_.bin",
            RomNaming.sanitize("Game / Sequel (USA) <v1>.bin"),
        )
        assertEquals("A_B_C", RomNaming.sanitize("A:B|C"))
    }

    @Test
    fun `trims trailing dots and spaces that providers would silently drop`() {
        assertEquals("Game (USA)", RomNaming.sanitize("Game (USA). "))
        assertEquals("Game (USA)", RomNaming.sanitize("   Game (USA)   "))
    }

    @Test
    fun `collapses runs of whitespace`() {
        assertEquals("Game (USA) (Rev 1).bin", RomNaming.sanitize("Game  (USA)   (Rev 1).bin"))
    }

    @Test
    fun `never produces an empty name`() {
        assertEquals("unnamed", RomNaming.sanitize("   "))
        assertEquals("unnamed", RomNaming.sanitize("..."))
    }

    @Test
    fun `truncates over-long names but keeps the extension`() {
        val long = "A".repeat(400) + ".sfc"

        val result = RomNaming.sanitize(long)

        assertTrue(result.endsWith(".sfc"))
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 240)
    }

    @Test
    fun `truncation does not split a multi-byte character`() {
        val long = "日".repeat(200) + ".bin"

        val result = RomNaming.sanitize(long)

        assertTrue(result.endsWith(".bin"))
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 240)
        // Round-tripping proves no character was cut in half.
        assertEquals(result, String(result.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test
    fun `control characters are replaced rather than dropped`() {
        // SOH and DEL: legal in a Kotlin string, rejected by real file systems.
        val raw = "Game" + 1.toChar() + "USA" + 127.toChar() + ".bin"

        assertEquals("Game_USA_.bin", RomNaming.sanitize(raw))
    }

    private fun entry(romName: String, gameName: String = romName.substringBeforeLast('.')) =
        DatEntry(
            game = DatGame(name = gameName),
            rom = DatRom(name = romName, size = 1024L, crc32 = "12345678", md5 = null, sha1 = null),
            source = "test",
        )
}
