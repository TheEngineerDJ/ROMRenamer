package com.romrenamer.app.core.rename

import com.romrenamer.app.core.dat.DatEntry
import com.romrenamer.app.core.storage.RomFile

/** How the official name is derived from a DAT entry. */
enum class NamingPolicy {
    /**
     * Use the DAT's `<rom name="…">` verbatim — the No-Intro / Redump standard, including
     * the extension the cataloguers chose (a `.smc` dump becomes `.sfc`).
     */
    DAT_ROM_NAME,

    /**
     * Use the DAT's game title but keep the extension already on disk. Useful when an
     * emulator or frontend is picky about extensions.
     */
    GAME_TITLE_KEEP_EXTENSION,
}

object RomNaming {

    /**
     * The name a file called [currentName] should end up with, given the DAT entry it
     * matched.
     *
     * For a ROM found inside a zip the official *title* is applied but the `.zip` extension
     * is kept, since the file being renamed is the archive rather than the ROM.
     */
    fun targetName(
        entry: DatEntry,
        currentName: String,
        fromArchive: Boolean = false,
        policy: NamingPolicy = NamingPolicy.DAT_ROM_NAME,
    ): String {
        val officialName = entry.officialFileName
        val extension = currentName.substringAfterLast('.', "").lowercase()
        val raw = when {
            fromArchive -> officialName.stripExtension() + extension.dotted()
            policy == NamingPolicy.DAT_ROM_NAME -> officialName
            else -> {
                val title = entry.game.name.ifBlank { officialName.stripExtension() }
                title + extension.dotted()
            }
        }
        return sanitize(raw)
    }

    /** Convenience overload for a discovered file. */
    fun targetName(
        entry: DatEntry,
        file: RomFile,
        fromArchive: Boolean = false,
        policy: NamingPolicy = NamingPolicy.DAT_ROM_NAME,
    ): String = targetName(entry, file.name, fromArchive, policy)

    /**
     * Makes a name safe to hand to a storage provider.
     *
     * FAT32 and exFAT — what most SD cards use — reject `\ / : * ? " < > |`, and trailing
     * dots or spaces are silently dropped by some providers, which would leave the file
     * named something other than what the preview promised. Characters are replaced rather
     * than removed so the result stays readable and stays unique.
     */
    fun sanitize(name: String): String {
        val cleaned = buildString(name.length) {
            for (ch in name) {
                append(
                    when {
                        ch in ILLEGAL_CHARS -> '_'
                        ch.code < 0x20 || ch.code == 0x7F -> '_'
                        else -> ch
                    },
                )
            }
        }
            .replace(MULTI_SPACE, " ")
            .trim()
            .trimEnd('.', ' ')

        val safe = cleaned.ifBlank { "unnamed" }
        return truncateToBytes(safe, MAX_NAME_BYTES)
    }

    /**
     * Shortens a name to fit a file-system limit without cutting the extension off or
     * splitting a multi-byte character.
     */
    private fun truncateToBytes(name: String, maxBytes: Int): String {
        if (name.toByteArray(Charsets.UTF_8).size <= maxBytes) return name

        val extension = name.substringAfterLast('.', "")
        val suffix = if (extension.isNotEmpty() && extension.length <= MAX_EXTENSION_LENGTH) {
            ".$extension"
        } else {
            ""
        }
        val budget = maxBytes - suffix.toByteArray(Charsets.UTF_8).size
        val base = if (suffix.isEmpty()) name else name.dropLast(suffix.length)

        var end = base.length
        while (end > 0 && base.substring(0, end).toByteArray(Charsets.UTF_8).size > budget) {
            end--
        }
        // Never split a surrogate pair; that would produce an unpaired code unit.
        if (end > 0 && base[end - 1].isHighSurrogate()) end--
        return base.substring(0, end).trimEnd('.', ' ') + suffix
    }

    private fun String.stripExtension(): String = substringBeforeLast('.', this)

    private fun String.dotted(): String = if (isEmpty()) "" else ".$this"

    private const val ILLEGAL_CHARS = "\\/:*?\"<>|"
    private val MULTI_SPACE = Regex("\\s{2,}")
    private const val MAX_NAME_BYTES = 240
    private const val MAX_EXTENSION_LENGTH = 10
}
