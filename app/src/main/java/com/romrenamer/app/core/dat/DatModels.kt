package com.romrenamer.app.core.dat

/**
 * Data model for Logiqx-style XML DAT files as published by No-Intro and Redump.
 *
 * A DAT looks like this (No-Intro, single-file systems):
 *
 * ```xml
 * <datafile>
 *   <header>
 *     <name>Nintendo - Super Nintendo Entertainment System</name>
 *     <version>20240101-123456</version>
 *   </header>
 *   <game name="Super Mario World (USA)">
 *     <description>Super Mario World (USA)</description>
 *     <rom name="Super Mario World (USA).sfc" size="524288"
 *          crc="B19ED489" md5="..." sha1="..."/>
 *   </game>
 * </datafile>
 * ```
 *
 * Redump DATs use the same shape but a game frequently holds several `<rom>` elements
 * (one per CD track, plus a `.cue` sheet), and newer exports may name the element
 * `<machine>` instead of `<game>`.
 */
data class DatHeader(
    val name: String? = null,
    val description: String? = null,
    val version: String? = null,
    val date: String? = null,
    val author: String? = null,
    val homepage: String? = null,
    val url: String? = null,
) {
    /** Best-effort display name for the collection this DAT describes. */
    val displayName: String
        get() = name ?: description ?: "Unnamed DAT"
}

/**
 * One `<rom>` (or `<disk>`) entry: the canonical file name plus its hashes.
 *
 * Hash strings are normalised to lower-case hex on construction so that lookups never
 * have to worry about the casing a particular DAT publisher used.
 */
data class DatRom(
    val name: String,
    val size: Long?,
    val crc32: String?,
    val md5: String?,
    val sha1: String?,
    val status: String? = null,
) {
    /** `true` for entries flagged `nodump`/`baddump`, whose hashes are unreliable. */
    val isDumpKnownBad: Boolean
        get() = status != null && (status == "nodump" || status == "baddump")
}

/** One `<game>`/`<machine>` entry, i.e. a single release in the collection. */
data class DatGame(
    val name: String,
    val description: String? = null,
    val category: String? = null,
    val cloneOf: String? = null,
    val roms: List<DatRom> = emptyList(),
)

/** A hit in the index: the release plus the specific file inside it that matched. */
data class DatEntry(
    val game: DatGame,
    val rom: DatRom,
    /** [DatHeader.displayName] of the DAT this entry came from. */
    val source: String,
) {
    /**
     * The official file name for this entry. Multi-file releases (Redump discs) keep the
     * per-track `<rom>` name; single-file releases fall back to the game name plus the
     * extension the DAT recorded.
     */
    val officialFileName: String get() = rom.name
}
