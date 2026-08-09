package com.romrenamer.app.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleNormalizerTest {

    @Test
    fun `strips the extension and bracketed region tags`() {
        assertEquals(
            "super mario world",
            TitleNormalizer.fromDatTitle("Super Mario World (USA) [!]").text,
        )
        assertEquals(
            "sonic the hedgehog 2",
            TitleNormalizer.fromDatTitle("Sonic The Hedgehog 2 (USA, Europe) (En,Fr,De)").text,
        )
    }

    @Test
    fun `a scene rip normalises onto its official title`() {
        val official = TitleNormalizer.fromDatTitle("Viewtiful Joe - Red Hot Rumble (USA)")
        val rip = TitleNormalizer.fromFileName("Viewtiful Joe - Red Hot Rumble-memorypsp.iso")

        assertTrue(rip.any { it.text == official.text })
    }

    @Test
    fun `an unknown scene group is stripped by the trailing-tag variant`() {
        val official = TitleNormalizer.fromDatTitle("Gran Turismo 4 (USA)")
        val rip = TitleNormalizer.fromFileName("Gran Turismo 4-SOMEGROUP99.iso")

        assertTrue(rip.any { it.text == official.text })
    }

    @Test
    fun `a hyphenated title keeps a variant with the hyphen intact`() {
        // Stripping the trailing token would leave "spider"; the unstripped variant has to
        // survive so the match can still be made on it.
        val variants = TitleNormalizer.fromFileName("Spider-Man.iso").map { it.text }

        assertTrue(variants.contains("spider man"))
    }

    @Test
    fun `a spaced hyphen is punctuation, not a tag separator`() {
        val variants = TitleNormalizer.fromFileName("Ratchet - Deadlocked.iso").map { it.text }

        assertEquals(listOf("ratchet deadlocked"), variants)
    }

    @Test
    fun `site watermarks and prefixes are removed`() {
        assertEquals(
            "metal gear solid",
            TitleNormalizer.fromFileName("www.cdromance.com - Metal Gear Solid.iso").first().text,
        )
        assertEquals(
            "final fantasy tactics",
            TitleNormalizer.fromFileName("Final Fantasy Tactics [ppsspp].iso").first().text,
        )
    }

    @Test
    fun `separators and case are folded away`() {
        assertEquals(
            "crash bandicoot 2 cortex strikes back",
            TitleNormalizer.fromFileName("crash_bandicoot_2.cortex.strikes.back.bin").first().text,
        )
    }

    @Test
    fun `roman numerals fold onto their digits`() {
        assertEquals(
            TitleNormalizer.fromDatTitle("Final Fantasy VII").text,
            TitleNormalizer.fromFileName("Final Fantasy 7.iso").first().text,
        )
    }

    @Test
    fun `single-letter numerals are left alone as words`() {
        // "I" here is the pronoun, not the number one.
        assertEquals("i am setsuna", TitleNormalizer.fromDatTitle("I Am Setsuna").text)
    }

    @Test
    fun `accents are folded so Pokemon matches Pokemon`() {
        assertEquals(
            TitleNormalizer.fromDatTitle("Pokémon Stadium").text,
            TitleNormalizer.fromFileName("Pokemon Stadium.z64").first().text,
        )
    }

    @Test
    fun `ampersands become the word`() {
        assertEquals(
            TitleNormalizer.fromDatTitle("Ratchet & Clank").text,
            TitleNormalizer.fromFileName("Ratchet and Clank.iso").first().text,
        )
    }

    @Test
    fun `container words are dropped from both sides equally`() {
        // "CD" is part of the real title, so dropping it symmetrically still matches.
        assertEquals(
            TitleNormalizer.fromDatTitle("Sonic CD (USA)").text,
            TitleNormalizer.fromFileName("Sonic CD.bin").first().text,
        )
    }

    @Test
    fun `names too short for edit distance to mean anything are rejected`() {
        assertTrue(TitleNormalizer.fromFileName("a.iso").isEmpty())
        assertTrue(TitleNormalizer.fromFileName("(USA).iso").isEmpty())
    }

    @Test
    fun `numeric tokens are kept apart from the rest`() {
        val fourth = TitleNormalizer.fromDatTitle("Gran Turismo 4 (USA)")
        val fifth = TitleNormalizer.fromFileName("Gran Turismo 5.iso").first()

        assertEquals(setOf("4"), fourth.numbers)
        assertEquals(setOf("5"), fifth.numbers)
    }

    @Test
    fun `a version stamp is not mistaken for a sequel number`() {
        val stamped = TitleNormalizer.fromFileName("Metal Gear Solid v1.2.iso").first()

        assertEquals(emptySet<String>(), stamped.numbers)
        assertEquals(TitleNormalizer.fromDatTitle("Metal Gear Solid (USA)").text, stamped.text)
    }

    @Test
    fun `tokens are exposed for set comparison`() {
        val normalized = TitleNormalizer.fromDatTitle("Super Mario World (USA)")

        assertEquals(setOf("super", "mario", "world"), normalized.tokens)
    }
}
