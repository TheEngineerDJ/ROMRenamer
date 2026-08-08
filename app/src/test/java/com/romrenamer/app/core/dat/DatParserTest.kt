package com.romrenamer.app.core.dat

import com.romrenamer.app.core.hash.HashAlgorithm
import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.kxml2.io.KXmlParser

/**
 * The parser is exercised on the JVM with kxml2, which is the same parser implementation
 * Android's `XmlPullParserFactory` hands out on device.
 */
class DatParserTest {

    private val parser = DatParser { KXmlParser() }

    @Test
    fun `parses a No-Intro style DAT`() = runTest {
        val builder = DatIndexBuilder()
        val result = parser.parse(NO_INTRO_DAT.stream(), builder)

        assertEquals("Nintendo - Super Nintendo Entertainment System", result.header.name)
        assertEquals("20240101-123456", result.header.version)
        assertEquals(2, result.gamesParsed)

        val index = builder.build()
        assertEquals(2, index.gameCount)
        assertEquals(2, index.romCount)
    }

    @Test
    fun `normalises hash casing and missing leading zeros`() = runTest {
        val builder = DatIndexBuilder()
        parser.parse(NO_INTRO_DAT.stream(), builder)
        val index = builder.build()

        // The DAT writes "B19ED489"; a computed CRC32 is lower case.
        val matches = index.findByCrc32("b19ed489")
        assertEquals(1, matches.size)
        assertEquals("Super Mario World (USA)", matches.first().game.name)

        // "7f1a2b" in the DAT is a CRC32 with its leading zeros dropped.
        val padded = index.findByCrc32("007f1a2b")
        assertEquals(1, padded.size)
        assertEquals("Zelda no Densetsu (Japan)", padded.first().game.name)
    }

    @Test
    fun `keeps every track of a Redump multi-file release`() = runTest {
        val builder = DatIndexBuilder()
        parser.parse(REDUMP_DAT.stream(), builder)
        val index = builder.build()

        assertEquals(1, index.gameCount)
        assertEquals(3, index.romCount)

        val track = index.findBySha1("da39a3ee5e6b4b0d3255bfef95601890afd80709")
        assertEquals(1, track.size)
        assertEquals("Some Game (USA) (Track 2).bin", track.first().officialFileName)
    }

    @Test
    fun `reads machine elements as games`() = runTest {
        val builder = DatIndexBuilder()
        parser.parse(MACHINE_DAT.stream(), builder)
        val index = builder.build()

        assertEquals(1, index.gameCount)
        assertEquals("Sonic The Hedgehog (USA, Europe)", index.findByCrc32("f9394e97").first().game.name)
    }

    @Test
    fun `skips entries whose dump is flagged bad`() = runTest {
        val builder = DatIndexBuilder()
        parser.parse(NODUMP_DAT.stream(), builder)
        val index = builder.build()

        assertTrue(index.findByCrc32("00000000").isEmpty())
        assertEquals(1, index.findByCrc32("12345678").size)
    }

    @Test
    fun `records which algorithms the DAT actually publishes`() = runTest {
        val builder = DatIndexBuilder()
        parser.parse(NO_INTRO_DAT.stream(), builder)
        val index = builder.build()

        assertTrue(HashAlgorithm.CRC32 in index.availableAlgorithms)
        assertTrue(HashAlgorithm.MD5 in index.availableAlgorithms)
        assertTrue(HashAlgorithm.SHA1 in index.availableAlgorithms)
    }

    @Test
    fun `size index answers hasSize and rejects unknown sizes`() = runTest {
        val builder = DatIndexBuilder()
        parser.parse(NO_INTRO_DAT.stream(), builder)
        val index = builder.build()

        assertTrue(index.hasSize(524288L))
        assertTrue(!index.hasSize(999_999_999L))
    }

    @Test
    fun `an incomplete size index disables the size shortcut`() = runTest {
        val builder = DatIndexBuilder()
        parser.parse(NO_SIZE_DAT.stream(), builder)
        val index = builder.build()

        // Nothing declared a size, so every size has to be treated as possible.
        assertTrue(index.hasSize(1L))
        assertTrue(index.hasSize(123_456L))
    }

    @Test
    fun `merges several DATs into one index`() = runTest {
        val builder = DatIndexBuilder()
        parser.parse(NO_INTRO_DAT.stream(), builder)
        parser.parse(MACHINE_DAT.stream(), builder)
        val index = builder.build()

        assertEquals(3, index.gameCount)
        assertNotNull(index.findByCrc32("b19ed489").firstOrNull())
        assertNotNull(index.findByCrc32("f9394e97").firstOrNull())
        assertEquals(2, index.headers.size)
    }

    @Test
    fun `rejects a ClrMamePro DAT with an actionable message`() = runTest {
        try {
            parser.parse(CLRMAMEPRO_DAT.stream(), DatIndexBuilder())
            fail("Expected a DatParseException")
        } catch (e: DatParseException) {
            assertTrue(e.message.orEmpty().contains("ClrMamePro"))
        }
    }

    @Test
    fun `rejects an XML file that holds no games`() = runTest {
        try {
            parser.parse("<datafile><header><name>Empty</name></header></datafile>".stream(), DatIndexBuilder())
            fail("Expected a DatParseException")
        } catch (e: DatParseException) {
            assertTrue(e.message.orEmpty().contains("No game entries"))
        }
    }

    @Test
    fun `ignores rom entries with no hashes at all`() = runTest {
        val builder = DatIndexBuilder()
        val result = parser.parse(HASHLESS_ROM_DAT.stream(), builder)

        assertEquals(1, result.gamesParsed)
        assertEquals(1, result.romsParsed)
        assertNull(builder.build().findByCrc32("deadbeef").firstOrNull()?.rom?.md5)
    }

    @Test
    fun `reports progress while parsing`() = runTest {
        val seen = mutableListOf<DatParseProgress>()
        parser.parse(NO_INTRO_DAT.stream(), DatIndexBuilder(), totalBytes = 1000L) { seen += it }

        assertTrue(seen.isNotEmpty())
        assertEquals(2, seen.last().gamesParsed)
    }

    private fun String.stream() = ByteArrayInputStream(toByteArray(Charsets.UTF_8))

    private companion object {
        val NO_INTRO_DAT = """
            <?xml version="1.0"?>
            <!DOCTYPE datafile PUBLIC "-//Logiqx//DTD ROM Management Datafile//EN" "http://www.logiqx.com/Dats/datafile.dtd">
            <datafile>
              <header>
                <name>Nintendo - Super Nintendo Entertainment System</name>
                <description>Nintendo - Super Nintendo Entertainment System</description>
                <version>20240101-123456</version>
                <homepage>No-Intro</homepage>
              </header>
              <game name="Super Mario World (USA)">
                <description>Super Mario World (USA)</description>
                <release name="Super Mario World (USA)" region="USA"/>
                <rom name="Super Mario World (USA).sfc" size="524288" crc="B19ED489"
                     md5="cdd3c8c37322978ca8669b34bc89c804"
                     sha1="6b47bb75d16514b6a476aa0c73a683a2a4c18765"/>
              </game>
              <game name="Zelda no Densetsu (Japan)">
                <description>Zelda no Densetsu (Japan)</description>
                <rom name="Zelda no Densetsu (Japan).sfc" size="1048576" crc="7f1a2b"
                     md5="0123456789abcdef0123456789abcdef"
                     sha1="0123456789abcdef0123456789abcdef01234567"/>
              </game>
            </datafile>
        """.trimIndent()

        val REDUMP_DAT = """
            <?xml version="1.0"?>
            <datafile>
              <header><name>Sony - PlayStation</name></header>
              <game name="Some Game (USA)">
                <description>Some Game (USA)</description>
                <rom name="Some Game (USA).cue" size="180" crc="AABBCCDD"
                     sha1="1111111111111111111111111111111111111111"/>
                <rom name="Some Game (USA) (Track 1).bin" size="700000000" crc="11223344"
                     sha1="2222222222222222222222222222222222222222"/>
                <rom name="Some Game (USA) (Track 2).bin" size="40000000" crc="55667788"
                     sha1="da39a3ee5e6b4b0d3255bfef95601890afd80709"/>
              </game>
            </datafile>
        """.trimIndent()

        val MACHINE_DAT = """
            <?xml version="1.0"?>
            <datafile>
              <header><name>Sega - Mega Drive - Genesis</name></header>
              <machine name="Sonic The Hedgehog (USA, Europe)">
                <description>Sonic The Hedgehog (USA, Europe)</description>
                <rom name="Sonic The Hedgehog (USA, Europe).md" size="524288" crc="F9394E97"/>
              </machine>
            </datafile>
        """.trimIndent()

        val NODUMP_DAT = """
            <?xml version="1.0"?>
            <datafile>
              <header><name>Test</name></header>
              <game name="Broken Dump">
                <rom name="Broken Dump.bin" size="1024" crc="00000000" status="nodump"/>
              </game>
              <game name="Good Dump">
                <rom name="Good Dump.bin" size="1024" crc="12345678"/>
              </game>
            </datafile>
        """.trimIndent()

        val NO_SIZE_DAT = """
            <?xml version="1.0"?>
            <datafile>
              <header><name>Sizeless</name></header>
              <game name="Mystery Game">
                <rom name="Mystery Game.bin" crc="0f0f0f0f"/>
              </game>
            </datafile>
        """.trimIndent()

        val HASHLESS_ROM_DAT = """
            <?xml version="1.0"?>
            <datafile>
              <header><name>Partial</name></header>
              <game name="Half Documented">
                <rom name="Half Documented (disc).cue" size="100"/>
                <rom name="Half Documented (disc).bin" size="200" crc="DEADBEEF"/>
              </game>
            </datafile>
        """.trimIndent()

        val CLRMAMEPRO_DAT = """
            clrmamepro (
                name "Nintendo - Game Boy"
                description "Nintendo - Game Boy"
            )
        """.trimIndent()
    }
}
