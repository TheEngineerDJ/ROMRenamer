package com.romrenamer.app.core.dat

import com.romrenamer.app.core.hash.HashAlgorithm
import com.romrenamer.app.core.hash.Hashes
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory

/** Raised when a file is not a DAT we can read, or is structurally broken. */
class DatParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Progress callback payload, emitted while a DAT is being read. */
data class DatParseProgress(
    val gamesParsed: Int,
    val bytesRead: Long,
    /** Total stream length if the caller knew it, else `null`. */
    val totalBytes: Long?,
) {
    val fraction: Float?
        get() = totalBytes
            ?.takeIf { it > 0 }
            ?.let { (bytesRead.toFloat() / it).coerceIn(0f, 1f) }
}

/** What a single parsed DAT contributed. */
data class DatParseResult(
    val header: DatHeader,
    val gamesParsed: Int,
    val romsParsed: Int,
)

/**
 * Streaming parser for Logiqx-style XML DATs (No-Intro, Redump, and compatible exports).
 *
 * [XmlPullParser] is used rather than DOM because these files are large — a full Redump
 * disc DAT runs to tens of megabytes and hundreds of thousands of elements — and we only
 * ever need a handful of attributes per entry. Nothing is buffered beyond the element
 * currently being read, so peak memory is the index itself.
 *
 * @param parserFactory injection point for tests; on device this yields the platform parser.
 */
class DatParser(
    private val parserFactory: () -> XmlPullParser = {
        XmlPullParserFactory.newInstance().newPullParser()
    },
) {

    /**
     * Reads [input] and feeds every game it contains into [builder].
     *
     * The stream is consumed but not closed — the caller owns it. Safe to call repeatedly
     * with the same builder to merge several DATs into one index.
     *
     * @param totalBytes stream length when known, used only to make progress determinate.
     * @throws DatParseException if the file is not a readable XML DAT.
     */
    suspend fun parse(
        input: InputStream,
        builder: DatIndexBuilder,
        totalBytes: Long? = null,
        onProgress: ((DatParseProgress) -> Unit)? = null,
    ): DatParseResult {
        val buffered = BufferedInputStream(input, STREAM_BUFFER_BYTES)
        requireXmlPrologue(buffered)
        val counting = CountingInputStream(buffered)

        val parser = try {
            parserFactory().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                // Passing a null encoding lets the parser honour the <?xml encoding="..."?>
                // declaration; several No-Intro DATs are not UTF-8.
                setInput(counting, null)
            }
        } catch (e: XmlPullParserException) {
            throw DatParseException("Could not initialise the XML parser: ${e.message}", e)
        }

        var header = DatHeader()
        var games = 0
        var roms = 0
        // The header names the collection, but it is parsed after the first games in some
        // hand-edited DATs, so games are staged and attributed once parsing finishes.
        val staged = ArrayList<DatGame>(INITIAL_GAME_CAPACITY)

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name?.lowercase()) {
                        TAG_HEADER -> header = readHeader(parser)
                        TAG_GAME, TAG_MACHINE -> {
                            val game = readGame(parser)
                            if (game.roms.isNotEmpty()) {
                                staged += game
                                games++
                                roms += game.roms.size
                                if (games % PROGRESS_INTERVAL == 0) {
                                    currentCoroutineContext().ensureActive()
                                    onProgress?.invoke(
                                        DatParseProgress(games, counting.bytesRead, totalBytes),
                                    )
                                }
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: XmlPullParserException) {
            throw DatParseException(
                "Malformed DAT at line ${parser.lineNumber}: ${e.message}",
                e,
            )
        } catch (e: IOException) {
            throw DatParseException("Could not read the DAT file: ${e.message}", e)
        }

        if (staged.isEmpty()) {
            throw DatParseException(
                "No game entries found. Is this a Logiqx XML DAT from No-Intro or Redump?",
            )
        }

        builder.addHeader(header)
        val source = header.displayName
        staged.forEach { builder.addGame(it, source) }
        onProgress?.invoke(DatParseProgress(games, counting.bytesRead, totalBytes))
        return DatParseResult(header, games, roms)
    }

    private fun readHeader(parser: XmlPullParser): DatHeader {
        var name: String? = null
        var description: String? = null
        var version: String? = null
        var date: String? = null
        var author: String? = null
        var homepage: String? = null
        var url: String? = null

        forEachChild(parser) {
            when (parser.name?.lowercase()) {
                "name" -> name = readText(parser).ifBlank { null }
                "description" -> description = readText(parser).ifBlank { null }
                "version" -> version = readText(parser).ifBlank { null }
                "date" -> date = readText(parser).ifBlank { null }
                "author" -> author = readText(parser).ifBlank { null }
                "homepage" -> homepage = readText(parser).ifBlank { null }
                "url" -> url = readText(parser).ifBlank { null }
                else -> skipElement(parser)
            }
        }
        return DatHeader(name, description, version, date, author, homepage, url)
    }

    private fun readGame(parser: XmlPullParser): DatGame {
        val name = parser.attr("name").orEmpty()
        var description: String? = null
        var category: String? = null
        val cloneOf = parser.attr("cloneof")
        val roms = mutableListOf<DatRom>()

        forEachChild(parser) {
            when (parser.name?.lowercase()) {
                "description" -> description = readText(parser).ifBlank { null }
                "category" -> category = readText(parser).ifBlank { null }
                TAG_ROM, TAG_DISK -> {
                    readRom(parser)?.let { roms += it }
                    skipElement(parser)
                }
                else -> skipElement(parser)
            }
        }

        return DatGame(
            name = name.ifBlank { description.orEmpty() },
            description = description,
            category = category,
            cloneOf = cloneOf,
            roms = roms,
        )
    }

    /** Returns `null` for entries with no usable name or no hashes at all. */
    private fun readRom(parser: XmlPullParser): DatRom? {
        val name = parser.attr("name")?.trim().orEmpty()
        if (name.isEmpty()) return null

        val crc32 = Hashes.normalize(
            parser.attr("crc") ?: parser.attr("crc32"),
            HashAlgorithm.CRC32,
        )
        val md5 = Hashes.normalize(parser.attr("md5"), HashAlgorithm.MD5)
        val sha1 = Hashes.normalize(parser.attr("sha1"), HashAlgorithm.SHA1)
        if (crc32 == null && md5 == null && sha1 == null) return null

        return DatRom(
            name = name,
            size = parser.attr("size")?.trim()?.toLongOrNull(),
            crc32 = crc32,
            md5 = md5,
            sha1 = sha1,
            status = parser.attr("status")?.trim()?.lowercase(),
        )
    }

    /**
     * Runs [body] at every direct child START_TAG of the element the parser is currently on,
     * then leaves the parser on that element's END_TAG.
     *
     * [body] must consume its element completely (via [readText] or [skipElement]); the depth
     * check makes a body that forgets to do so fail loudly rather than silently mis-parse.
     */
    private inline fun forEachChild(parser: XmlPullParser, body: () -> Unit) {
        val depth = parser.depth
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    if (parser.depth != depth + 1) continue
                    body()
                }
                XmlPullParser.END_TAG -> if (parser.depth == depth) return
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** Reads the text content of the current element, leaving the parser on its END_TAG. */
    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        val depth = parser.depth
        while (true) {
            when (parser.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF ->
                    parser.text?.let(sb::append)
                XmlPullParser.END_TAG -> if (parser.depth == depth) return sb.toString().trim()
                XmlPullParser.END_DOCUMENT -> return sb.toString().trim()
            }
        }
    }

    /** Advances past the current element and everything inside it. */
    private fun skipElement(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun XmlPullParser.attr(name: String): String? = getAttributeValue(null, name)

    /**
     * Fails fast on the most common wrong-file mistake: ClrMamePro-format DATs, which are
     * plain text and would otherwise surface as an opaque XML syntax error on line 1.
     */
    private fun requireXmlPrologue(input: BufferedInputStream) {
        input.mark(PROLOGUE_PROBE_BYTES)
        val probe = ByteArray(PROLOGUE_PROBE_BYTES)
        val read = try {
            input.read(probe)
        } catch (e: IOException) {
            throw DatParseException("Could not read the DAT file: ${e.message}", e)
        }
        input.reset()
        if (read <= 0) throw DatParseException("The selected DAT file is empty.")

        // A UTF-16 BOM means the bytes are not single-byte text; let the parser sort it out.
        if (read >= 2 && probe.startsWith(UTF16_LE_BOM)) return
        if (read >= 2 && probe.startsWith(UTF16_BE_BOM)) return
        val offset = if (read >= 3 && probe.startsWith(UTF8_BOM)) UTF8_BOM.size else 0

        val head = String(probe, offset, read - offset, Charsets.ISO_8859_1).trimStart()
        if (head.startsWith("<")) return
        if (head.startsWith("clrmamepro", ignoreCase = true) || head.startsWith("game (")) {
            throw DatParseException(
                "This looks like a ClrMamePro-format DAT. Please download the XML " +
                    "(Logiqx) version from No-Intro or Redump.",
            )
        }
        throw DatParseException("The selected file is not an XML DAT.")
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private companion object {
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

        const val TAG_HEADER = "header"
        const val TAG_GAME = "game"
        const val TAG_MACHINE = "machine"
        const val TAG_ROM = "rom"
        const val TAG_DISK = "disk"

        const val STREAM_BUFFER_BYTES = 64 * 1024
        const val PROLOGUE_PROBE_BYTES = 256
        const val PROGRESS_INTERVAL = 512
        const val INITIAL_GAME_CAPACITY = 4096
    }
}

/** Counts bytes pulled through the stream so DAT parsing can report determinate progress. */
private class CountingInputStream(private val delegate: InputStream) : InputStream() {

    @Volatile
    var bytesRead: Long = 0L
        private set

    override fun read(): Int = delegate.read().also { if (it >= 0) bytesRead++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        delegate.read(b, off, len).also { if (it > 0) bytesRead += it }

    override fun available(): Int = delegate.available()

    override fun close() = delegate.close()
}
