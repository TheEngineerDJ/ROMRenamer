package com.romrenamer.app.core.match

import com.romrenamer.app.core.dat.DatGame
import com.romrenamer.app.core.dat.DatIndexBuilder
import com.romrenamer.app.core.dat.DatRom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyTitleMatcherTest {

    @Test
    fun `matches a scene rip whose bytes could never match`() {
        val matcher = matcherOf(game("Viewtiful Joe - Red Hot Rumble (USA)", "iso"))

        val result = matcher.match("Viewtiful Joe - Red Hot Rumble-memorypsp.iso", "iso")

        assertTrue("was $result", result is FuzzyResult.Matched)
        result as FuzzyResult.Matched
        assertEquals("Viewtiful Joe - Red Hot Rumble (USA).iso", result.entry.officialFileName)
        assertEquals(1f, result.confidence, 0.0001f)
    }

    @Test
    fun `matches through region tags and separator noise`() {
        val matcher = matcherOf(game("Crash Bandicoot 2 - Cortex Strikes Back (USA)", "bin"))

        val result = matcher.match("crash_bandicoot_2.cortex.strikes.back.bin", "bin")

        assertTrue(result is FuzzyResult.Matched)
        assertEquals(
            "Crash Bandicoot 2 - Cortex Strikes Back (USA).bin",
            (result as FuzzyResult.Matched).entry.officialFileName,
        )
    }

    @Test
    fun `survives a typo in the rip's file name`() {
        val matcher = matcherOf(game("Castlevania - Symphony of the Night (USA)", "bin"))

        val result = matcher.match("Castlevania - Symphony of the Nigth.bin", "bin")

        assertTrue(result is FuzzyResult.Matched)
        assertTrue((result as FuzzyResult.Matched).confidence >= 0.85f)
    }

    @Test
    fun `refuses an abbreviation that identifies nothing`() {
        // "su-dbz" is a real scene name, and it is genuinely not enough to go on.
        // Guessing here would rename a file to the wrong game.
        val matcher = matcherOf(game("Dragon Ball Z - Shin Budokai (USA)", "iso"))

        assertEquals(FuzzyResult.NoMatch, matcher.match("su-dbz.iso", "iso"))
    }

    @Test
    fun `refuses a different game in the same series`() {
        val matcher = matcherOf(
            game("Gran Turismo 3 - A-Spec (USA)", "iso"),
            game("Gran Turismo 4 (USA)", "iso"),
        )

        assertEquals(FuzzyResult.NoMatch, matcher.match("Gran Turismo 5.iso", "iso"))
    }

    @Test
    fun `two regions of the same game are ambiguous, not a guess`() {
        // Region tags are stripped for comparison, so these normalise identically. Picking
        // one would be a coin flip that silently mislabels the file.
        val matcher = matcherOf(
            game("Metal Gear Solid (USA)", "bin"),
            game("Metal Gear Solid (Europe)", "bin"),
        )

        val result = matcher.match("Metal Gear Solid.bin", "bin")

        assertTrue("was $result", result is FuzzyResult.Ambiguous)
        assertEquals(2, (result as FuzzyResult.Ambiguous).candidates.size)
    }

    @Test
    fun `a stricter threshold rejects what a looser one accepts`() {
        val games = arrayOf(game("Castlevania - Symphony of the Night (USA)", "bin"))

        val loose = matcherOf(*games, threshold = 0.8f)
        val strict = matcherOf(*games, threshold = 0.99f)
        val name = "Castlevania - Symphony of the Nigth.bin"

        assertTrue(loose.match(name, "bin") is FuzzyResult.Matched)
        assertEquals(FuzzyResult.NoMatch, strict.match(name, "bin"))
    }

    @Test
    fun `picks the track matching the local extension on a multi-file release`() {
        val redump = DatGame(
            name = "Some Game (USA)",
            roms = listOf(
                rom("Some Game (USA).cue", "aaaaaaaa"),
                rom("Some Game (USA) (Track 1).bin", "bbbbbbbb"),
            ),
        )
        val matcher = matcherOf(redump)

        val result = matcher.match("Some Game.cue", "cue")

        assertTrue(result is FuzzyResult.Matched)
        assertEquals("Some Game (USA).cue", (result as FuzzyResult.Matched).entry.officialFileName)
    }

    @Test
    fun `declines a multi-file release when the extension cannot pick a track`() {
        val redump = DatGame(
            name = "Some Game (USA)",
            roms = listOf(
                rom("Some Game (USA) (Track 1).bin", "aaaaaaaa"),
                rom("Some Game (USA) (Track 2).bin", "bbbbbbbb"),
            ),
        )
        val matcher = matcherOf(redump)

        // Renaming to the wrong track is worse than leaving the file alone.
        assertEquals(FuzzyResult.NoMatch, matcher.match("Some Game.bin", "bin"))
    }

    @Test
    fun `an empty database matches nothing`() {
        val matcher = FuzzyTitleMatcher.build(DatIndexBuilder().build())

        assertEquals(0, matcher.titleCount)
        assertEquals(FuzzyResult.NoMatch, matcher.match("Super Mario World.sfc", "sfc"))
    }

    @Test
    fun `the same title merged from two DATs is not treated as ambiguous`() {
        val builder = DatIndexBuilder()
        builder.addGame(game("Super Mario World (USA)", "sfc"), source = "No-Intro SNES")
        builder.addGame(game("Super Mario World (USA)", "sfc"), source = "No-Intro SNES (older)")
        val matcher = FuzzyTitleMatcher.build(builder.build())

        assertEquals(1, matcher.titleCount)
        assertTrue(matcher.match("Super Mario World.sfc", "sfc") is FuzzyResult.Matched)
    }

    @Test
    fun `the matched entry carries the DAT it came from`() {
        val builder = DatIndexBuilder()
        builder.addGame(game("Super Mario World (USA)", "sfc"), source = "No-Intro SNES")
        val matcher = FuzzyTitleMatcher.build(builder.build())

        val result = matcher.match("Super Mario World.sfc", "sfc")

        assertEquals("No-Intro SNES", (result as FuzzyResult.Matched).entry.source)
    }

    private fun game(name: String, extension: String) = DatGame(
        name = name,
        roms = listOf(rom("$name.$extension", "12345678")),
    )

    private fun rom(name: String, crc: String) =
        DatRom(name = name, size = 1024L, crc32 = crc, md5 = null, sha1 = null)

    private fun matcherOf(
        vararg games: DatGame,
        threshold: Float = FuzzyTitleMatcher.DEFAULT_THRESHOLD,
    ): FuzzyTitleMatcher {
        val builder = DatIndexBuilder()
        games.forEach { builder.addGame(it, source = "test") }
        return FuzzyTitleMatcher.build(builder.build(), threshold)
    }
}
