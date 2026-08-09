package com.romrenamer.app.core.storage

import android.net.Uri

/** A single file discovered under the user-granted directory tree. */
data class RomFile(
    /** Tree-backed document URI; usable for reading and for [android.provider.DocumentsContract] renames. */
    val uri: Uri,
    /** Document URI of the containing directory, used to detect name collisions before renaming. */
    val parentUri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String?,
    /** Directory path relative to the selected tree, e.g. `"SNES/USA"`; empty at the root. */
    val relativePath: String,
    val lastModified: Long,
) {
    /** Lower-case extension without the dot, or empty when the name has none. */
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()

    val nameWithoutExtension: String
        get() = if (extension.isEmpty()) name else name.substring(0, name.length - extension.length - 1)

    /** Path shown in the UI, including the file name. */
    val displayPath: String
        get() = if (relativePath.isEmpty()) name else "$relativePath/$name"
}

/** Which files a scan should pick up. */
data class RomFileFilter(
    /**
     * Lower-case extensions (no dot) to include. `null` accepts every file, which is the
     * right choice for obscure systems — matching is by hash, so a wrong guess here only
     * costs scan time.
     */
    val extensions: Set<String>? = DEFAULT_ROM_EXTENSIONS,
    val minSizeBytes: Long = 1L,
    val maxSizeBytes: Long = Long.MAX_VALUE,
    val includeHidden: Boolean = false,
    val maxDepth: Int = 16,
) {
    fun accepts(name: String, size: Long): Boolean {
        if (!includeHidden && name.startsWith(".")) return false
        if (size < minSizeBytes || size > maxSizeBytes) return false
        val allowed = extensions ?: return true
        return name.substringAfterLast('.', "").lowercase() in allowed
    }

    companion object {
        /** Common cartridge/disc dumps plus the archive formats ROMs usually ship in. */
        val DEFAULT_ROM_EXTENSIONS: Set<String> = setOf(
            // Nintendo
            "nes", "fds", "unf", "unif", "sfc", "smc", "fig", "swc",
            "n64", "z64", "v64", "ndd", "gb", "gbc", "gba", "nds", "dsi", "3ds", "cia",
            "gcm", "rvz", "wbfs", "nsp", "xci",
            // Sega
            "sms", "gg", "sg", "md", "smd", "gen", "32x", "sc", "cue", "gdi",
            // Sony / disc images
            "iso", "bin", "img", "chd", "pbp", "ccd", "mdf", "nrg",
            // Atari / NEC / SNK / other
            "a26", "a52", "a78", "lnx", "jag", "j64", "col", "int", "vec", "ws", "wsc",
            "pce", "sgx", "ngp", "ngc", "vb", "min", "d64", "t64", "tap", "adf", "dsk",
            // Archives
            "zip", "7z", "rar",
        )

        /** Extensions used by No-Intro and Redump DAT downloads. */
        val DAT_EXTENSIONS: Set<String> = setOf("dat", "xml")

        /** Picks up every DAT in a folder tree, however deeply it is nested. */
        val DAT_FILES: RomFileFilter = RomFileFilter(extensions = DAT_EXTENSIONS)
    }
}
