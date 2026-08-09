package com.romrenamer.app.core.dat

import android.net.Uri
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One DAT file the user selected, either individually or by picking its folder. */
data class DatSource(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long? = null,
    /** Folder path relative to the selected tree, for disambiguating same-named DATs. */
    val relativePath: String = "",
) {
    val displayPath: String get() = if (relativePath.isEmpty()) name else "$relativePath/$name"
}

/** What happened to one selected file. */
sealed interface DatSourceOutcome {
    val source: DatSource

    data class Loaded(
        override val source: DatSource,
        val header: DatHeader,
        val games: Int,
        val roms: Int,
    ) : DatSourceOutcome

    /** Not a DAT, unreadable, or skipped because the merge hit its ceiling. */
    data class Rejected(override val source: DatSource, val reason: String) : DatSourceOutcome
}

data class DatLoadProgress(
    /** 1-based position of the file being read. */
    val fileNumber: Int,
    val fileCount: Int,
    val fileName: String,
    val gamesParsed: Int,
    /** Progress through the current file, when its size was known. */
    val fileFraction: Float?,
) {
    /** Progress across the whole selection, counting a size-less file as all-or-nothing. */
    val overallFraction: Float?
        get() = if (fileCount <= 0) {
            null
        } else {
            ((fileNumber - 1 + (fileFraction ?: 0f)) / fileCount).coerceIn(0f, 1f)
        }
}

data class DatLoadResult(
    val index: DatIndex,
    val outcomes: List<DatSourceOutcome>,
    /** `true` when the merge stopped early because it hit [DatLoader.maxRomEntries]. */
    val truncated: Boolean = false,
) {
    val loaded: List<DatSourceOutcome.Loaded> get() = outcomes.filterIsInstance<DatSourceOutcome.Loaded>()
    val rejected: List<DatSourceOutcome.Rejected> get() = outcomes.filterIsInstance<DatSourceOutcome.Rejected>()
    val loadedCount: Int get() = loaded.size
    val rejectedCount: Int get() = rejected.size
}

/**
 * Parses a whole selection of DAT files into one merged [DatIndex].
 *
 * Files are read one at a time and folded into a single builder, so the user can point the
 * app at a folder of DATs and match a mixed ROM library against every system at once
 * without choosing the right file up front.
 *
 * A selection made by folder will contain files that are not DATs at all. One bad file must
 * never cost the user the other ninety-nine, so every failure is recorded against its source
 * and the load continues. [DatParser] stages a file's games and only commits them once it
 * has parsed cleanly, so a file that fails halfway contributes nothing to the index.
 *
 * @param open supplies the bytes for a source; injected so the loader stays testable
 *   without a `ContentResolver`.
 * @param maxRomEntries ceiling on merged entries. A full No-Intro collection runs to
 *   millions of entries and would exhaust a phone's heap, so the merge stops and says so
 *   rather than dying.
 */
class DatLoader(
    private val open: (DatSource) -> InputStream,
    private val parser: DatParser = DatParser(),
    val maxRomEntries: Int = DEFAULT_MAX_ROM_ENTRIES,
) {

    suspend fun load(
        sources: List<DatSource>,
        onProgress: ((DatLoadProgress) -> Unit)? = null,
    ): DatLoadResult {
        val builder = DatIndexBuilder()
        val outcomes = mutableListOf<DatSourceOutcome>()
        var truncated = false
        // The same file can arrive twice when a user picks it individually and again via
        // its folder; indexing it twice would make every one of its games look ambiguous.
        val unique = sources.distinctBy { it.uri }

        unique.forEachIndexed { position, source ->
            currentCoroutineContext().ensureActive()

            if (builder.indexedRomCount >= maxRomEntries) {
                truncated = true
                outcomes += DatSourceOutcome.Rejected(
                    source,
                    "Skipped — the merged database is already at its size limit.",
                )
                return@forEachIndexed
            }

            onProgress?.invoke(
                DatLoadProgress(position + 1, unique.size, source.name, builder.gameCount, null),
            )

            outcomes += try {
                val result = open(source).use { stream ->
                    parser.parse(stream, builder, source.sizeBytes) { progress ->
                        onProgress?.invoke(
                            DatLoadProgress(
                                fileNumber = position + 1,
                                fileCount = unique.size,
                                fileName = source.name,
                                gamesParsed = progress.gamesParsed,
                                fileFraction = progress.fraction,
                            ),
                        )
                    }
                }
                DatSourceOutcome.Loaded(source, result.header, result.gamesParsed, result.romsParsed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DatParseException) {
                DatSourceOutcome.Rejected(source, e.message ?: "Not a readable DAT file.")
            } catch (e: Exception) {
                DatSourceOutcome.Rejected(source, e.message ?: "Could not read this file.")
            }
        }

        return DatLoadResult(builder.build(), outcomes, truncated)
    }

    companion object {
        /**
         * Roughly the size of a large multi-system collection. Past this the merged index
         * costs more heap than a phone can spare.
         */
        const val DEFAULT_MAX_ROM_ENTRIES = 1_200_000
    }
}
