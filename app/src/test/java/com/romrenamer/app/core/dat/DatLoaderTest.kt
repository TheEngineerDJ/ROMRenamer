package com.romrenamer.app.core.dat

import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

/**
 * The loader is what turns "point at a folder of DATs" into a single database, so these
 * cover the awkward parts of a real folder: files that are not DATs, duplicates, and a
 * selection large enough to need a ceiling.
 */
class DatLoaderTest {

    private val parser = DatParser { KXmlParser() }

    @Test
    fun `merges several DATs into one index`() = runTest {
        val result = loaderFor(SNES_DAT, MEGADRIVE_DAT).load(sources("snes.dat", "md.dat"))

        assertEquals(2, result.loadedCount)
        assertEquals(0, result.rejectedCount)
        assertEquals(3, result.index.gameCount)
        assertEquals(2, result.index.sourceCount)

        // Both systems answer from the same index.
        assertEquals(
            "Super Mario World (USA)",
            result.index.findByCrc32("b19ed489").first().game.name,
        )
        assertEquals(
            "Sonic The Hedgehog (USA, Europe)",
            result.index.findByCrc32("f9394e97").first().game.name,
        )
    }

    @Test
    fun `one unusable file does not cost the rest of the selection`() = runTest {
        val loader = loaderFor(SNES_DAT, NOT_A_DAT, MEGADRIVE_DAT)

        val result = loader.load(sources("snes.dat", "readme.xml", "md.dat"))

        assertEquals(2, result.loadedCount)
        assertEquals(1, result.rejectedCount)
        assertEquals("readme.xml", result.rejected.single().source.name)
        assertNotNull(result.index.findByCrc32("f9394e97").firstOrNull())
    }

    @Test
    fun `a file that fails midway contributes nothing to the index`() = runTest {
        val loader = loaderFor(TRUNCATED_DAT, MEGADRIVE_DAT)

        val result = loader.load(sources("broken.dat", "md.dat"))

        assertEquals(1, result.rejectedCount)
        // The half-parsed file listed a game before the XML broke; it must not be indexed.
        assertTrue(result.index.findByCrc32("11111111").isEmpty())
        // Only the two games from the DAT that parsed cleanly.
        assertEquals(2, result.index.gameCount)
    }

    @Test
    fun `reports an unreadable file against its source`() = runTest {
        val loader = DatLoader(
            open = { throw IOException("Permission denied") },
            parser = parser,
        )

        val result = loader.load(sources("locked.dat"))

        assertEquals(0, result.loadedCount)
        assertEquals("Permission denied", result.rejected.single().reason)
        assertTrue(result.index.isEmpty)
    }

    @Test
    fun `the same file selected twice is only indexed once`() = runTest {
        val contents = mapOf("snes.dat" to SNES_DAT)
        val loader = DatLoader(open = { ByteArrayInputStream(contents.getValue(it.name).toByteArray()) }, parser = parser)
        val duplicate = DatSource(Uri.parse("content://dats/snes.dat"), "snes.dat")

        val result = loader.load(listOf(duplicate, duplicate))

        assertEquals(1, result.loadedCount)
        // Indexed twice, every game in it would look like an ambiguous match.
        assertEquals(1, result.index.findByCrc32("b19ed489").size)
    }

    @Test
    fun `stops merging at the entry ceiling instead of exhausting memory`() = runTest {
        val loader = DatLoader(
            open = { ByteArrayInputStream(SNES_DAT.toByteArray()) },
            parser = parser,
            maxRomEntries = 1,
        )

        val result = loader.load(sources("a.dat", "b.dat", "c.dat"))

        assertTrue(result.truncated)
        assertEquals(1, result.loadedCount)
        assertEquals(2, result.rejectedCount)
        // What did load is still a usable database.
        assertNotNull(result.index.findByCrc32("b19ed489").firstOrNull())
    }

    @Test
    fun `an empty selection yields an empty index rather than failing`() = runTest {
        val result = loaderFor().load(emptyList())

        assertTrue(result.index.isEmpty)
        assertEquals(0, result.loadedCount)
        assertTrue(!result.truncated)
    }

    @Test
    fun `progress walks through the selection`() = runTest {
        val seen = mutableListOf<DatLoadProgress>()

        loaderFor(SNES_DAT, MEGADRIVE_DAT).load(sources("snes.dat", "md.dat")) { seen += it }

        assertEquals(listOf(1, 2), seen.map { it.fileNumber }.distinct())
        assertTrue(seen.all { it.fileCount == 2 })
        assertEquals(1f, seen.last().overallFraction!!, 0.5f)
    }

    @Test
    fun `overall progress spans the whole selection, not one file`() {
        val halfwayThroughFirstOfFour =
            DatLoadProgress(fileNumber = 1, fileCount = 4, fileName = "a", gamesParsed = 0, fileFraction = 0.5f)

        assertEquals(0.125f, halfwayThroughFirstOfFour.overallFraction!!, 0.0001f)
    }

    private fun sources(vararg names: String): List<DatSource> =
        names.map { DatSource(Uri.parse("content://dats/$it"), it) }

    /** Serves the given contents in order, one per source. */
    private fun loaderFor(vararg contents: String): DatLoader {
        val remaining = contents.toMutableList()
        val open: (DatSource) -> InputStream = {
            ByteArrayInputStream(remaining.removeAt(0).toByteArray(Charsets.UTF_8))
        }
        return DatLoader(open = open, parser = parser)
    }

    private companion object {
        val SNES_DAT = """
            <?xml version="1.0"?>
            <datafile>
              <header><name>Nintendo - Super Nintendo Entertainment System</name></header>
              <game name="Super Mario World (USA)">
                <rom name="Super Mario World (USA).sfc" size="524288" crc="B19ED489"/>
              </game>
            </datafile>
        """.trimIndent()

        val MEGADRIVE_DAT = """
            <?xml version="1.0"?>
            <datafile>
              <header><name>Sega - Mega Drive - Genesis</name></header>
              <game name="Sonic The Hedgehog (USA, Europe)">
                <rom name="Sonic The Hedgehog (USA, Europe).md" size="524288" crc="F9394E97"/>
              </game>
              <game name="Streets of Rage (USA, Europe)">
                <rom name="Streets of Rage (USA, Europe).md" size="524288" crc="1234ABCD"/>
              </game>
            </datafile>
        """.trimIndent()

        /** Valid XML that is not a DAT — the kind of thing a DAT folder is full of. */
        val NOT_A_DAT = """
            <?xml version="1.0"?>
            <notes><item>Downloaded 2024-01-01</item></notes>
        """.trimIndent()

        /** A game is listed, then the document ends mid-element. */
        val TRUNCATED_DAT = """
            <?xml version="1.0"?>
            <datafile>
              <header><name>Broken</name></header>
              <game name="Half Written (USA)">
                <rom name="Half Written (USA).bin" size="1024" crc="11111111"/>
              </game>
              <game name="Cut Off
        """.trimIndent()
    }
}
