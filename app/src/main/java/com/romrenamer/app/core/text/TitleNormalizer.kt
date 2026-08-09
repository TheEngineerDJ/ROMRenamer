package com.romrenamer.app.core.text

import java.text.Normalizer

/** A title reduced to a comparable form: canonical text plus its token set. */
data class NormalizedTitle(
    val text: String,
    val tokens: Set<String>,
    /**
     * The purely numeric tokens, kept apart because they are not interchangeable with the
     * rest. Edit distance sees one character between "gran turismo 4" and "gran turismo 5"
     * and calls them 93% alike; to a person they are different games. The matcher requires
     * these to agree exactly.
     */
    val numbers: Set<String> = tokens.filterTo(mutableSetOf()) { token -> token.all(Char::isDigit) },
) {
    val isUsable: Boolean
        get() = text.length >= MIN_USABLE_LENGTH && tokens.isNotEmpty()

    companion object {
        /**
         * Below this a name is too small for edit distance to mean anything — one character
         * out of two is a 50% difference.
         */
        const val MIN_USABLE_LENGTH = 3

        val EMPTY = NormalizedTitle("", emptySet(), emptySet())
    }
}

/**
 * Reduces DAT titles and local file names to a common form so they can be compared as text.
 *
 * The same normalisation runs over both sides, which is what makes aggressive rules safe:
 * dropping "cd" turns "Sonic CD" into "sonic" on the DAT side too, so the pair still
 * matches. The risk is not a missed match but two different games collapsing onto the same
 * text — the matcher handles that by reporting a tie as ambiguous rather than guessing.
 *
 * Scene rips are the reason this exists. A file named
 * `Viewtiful Joe - Red Hot Rumble-memorypsp.iso` is byte-for-byte different from the
 * catalogued dump, so no hash will ever match it, but its name still identifies the game.
 */
object TitleNormalizer {

    /** Canonical form of an official DAT title. */
    fun fromDatTitle(title: String): NormalizedTitle = normalize(title)

    /**
     * Candidate forms of a local file name, best-guess first.
     *
     * More than one is produced because tag-stripping cannot be done safely in one pass:
     * removing the trailing `-token` rescues `…Rumble-memorypsp` but would butcher
     * `Spider-Man`. Scoring every variant and keeping the best means an over-eager strip can
     * only ever fail to help, never cause a wrong match.
     */
    fun fromFileName(fileName: String): List<NormalizedTitle> {
        val base = stripSitePrefix(fileName.substringBeforeLast('.', fileName))
        val variants = LinkedHashSet<String>()
        variants += base
        trailingTagStripped(base)?.let { variants += it }

        return variants
            .map(::normalize)
            .filter { it.isUsable }
            .distinctBy { it.text }
    }

    /**
     * Drops a trailing `-tag`, the usual shape of a scene group or site watermark.
     *
     * Returns `null` when the trailing segment does not look like a tag — too long, or
     * spaced away from the hyphen as in `Ratchet - Deadlocked`, where the hyphen is
     * punctuation rather than a separator.
     */
    private fun trailingTagStripped(base: String): String? {
        val hyphen = base.lastIndexOf('-')
        if (hyphen <= 0) return null
        val tag = base.substring(hyphen + 1)
        if (tag.isBlank() || tag.length > MAX_TAG_LENGTH) return null
        if (tag.any { it.isWhitespace() }) return null
        if (base[hyphen - 1].isWhitespace()) return null
        return base.substring(0, hyphen)
    }

    /** Removes a leading `www.site.com -` style watermark. */
    private fun stripSitePrefix(name: String): String =
        SITE_PREFIX.replace(name, "").ifBlank { name }

    private fun normalize(raw: String): NormalizedTitle {
        val withoutBrackets = BRACKETED.replace(raw, " ")
        val folded = stripDiacritics(withoutBrackets)
            .lowercase()
            .replace("&", " and ")
            // Version stamps have to go before tokenising: the digits in "v1.2" would
            // otherwise look like a sequel number and block every match.
            .replace(VERSION_STAMP, " ")

        val tokens = folded
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .asSequence()
            .filter { it.isNotBlank() }
            .map(::canonicalToken)
            .filter { it !in DROPPED_TOKENS }
            .toList()

        if (tokens.isEmpty()) return NormalizedTitle.EMPTY
        return NormalizedTitle(text = tokens.joinToString(" "), tokens = tokens.toSet())
    }

    /**
     * Folds spellings that differ only cosmetically, most usefully roman numerals —
     * "Final Fantasy VII" and "final fantasy 7" name the same game.
     *
     * Single-letter numerals are left alone: "i", "v" and "x" are far more often words or
     * initials than numbers.
     */
    private fun canonicalToken(token: String): String = ROMAN_NUMERALS[token] ?: token

    /** Strips accents so "Pokémon" and "pokemon" compare equal. */
    private fun stripDiacritics(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")

    private val BRACKETED = Regex("""[(\[{][^)\]}]*[)\]}]""")
    private val VERSION_STAMP = Regex("""\bv\d+(\.\d+)*\b""")
    private val COMBINING_MARKS = Regex("""\p{Mn}+""")
    private val SITE_PREFIX = Regex("""^\s*(www\.)?[a-z0-9-]+\.(com|net|org|to|me|io|ru)\s*[-_ ]+""", RegexOption.IGNORE_CASE)

    private const val MAX_TAG_LENGTH = 20

    private val ROMAN_NUMERALS = mapOf(
        "ii" to "2", "iii" to "3", "iv" to "4", "vi" to "6", "vii" to "7",
        "viii" to "8", "ix" to "9", "xi" to "11", "xii" to "12", "xiii" to "13",
        "xiv" to "14", "xv" to "15", "xvi" to "16", "xvii" to "17", "xviii" to "18",
        "xix" to "19", "xx" to "20",
    )

    /**
     * Tokens that carry no identity. Container and dump descriptors, then the site and
     * scene noise that ends up welded onto rip file names.
     */
    private val DROPPED_TOKENS = setOf(
        // Container and dump descriptors.
        "iso", "bin", "cue", "img", "chd", "rom", "roms", "romset", "dump", "image",
        "disc", "disk", "cd", "dvd", "umd", "track",
        // Modification and packaging descriptors.
        "repack", "proper", "readnfo", "nfo", "scrubbed", "trimmed", "compressed",
        "decrypted", "encrypted", "undub", "patched", "fixed", "cracked", "full",
        // Site watermarks.
        "memorypsp", "cdromance", "romsmania", "romulation", "emuparadise", "portalroms",
        "wowroms", "ziperto", "nsw2u", "vimm", "downloadgamepsp", "ppsspp", "pspiso",
        "www", "com", "net", "org",
        // Scene groups seen on console rips.
        "skidrow", "reloaded", "razor1911", "codex", "plaza", "hoodlum", "prophet",
    )
}
