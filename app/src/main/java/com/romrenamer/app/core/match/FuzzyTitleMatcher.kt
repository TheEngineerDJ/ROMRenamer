package com.romrenamer.app.core.match

import com.romrenamer.app.core.dat.DatEntry
import com.romrenamer.app.core.dat.DatGame
import com.romrenamer.app.core.dat.DatIndex
import com.romrenamer.app.core.dat.DatRom
import com.romrenamer.app.core.text.NormalizedTitle
import com.romrenamer.app.core.text.StringSimilarity
import com.romrenamer.app.core.text.TitleNormalizer
import kotlin.math.abs

/** Outcome of matching a file name against the DAT titles. */
sealed interface FuzzyResult {

    data class Matched(val entry: DatEntry, val confidence: Float, val query: String) : FuzzyResult

    /** Several titles score equally well; renaming would be a coin flip. */
    data class Ambiguous(val candidates: List<DatEntry>, val confidence: Float) : FuzzyResult

    data object NoMatch : FuzzyResult
}

/**
 * Last-resort matching by file name, for ROMs whose bytes no longer match the catalogue.
 *
 * Scrubbed, trimmed, and re-encoded scene rips are common, and every one of them fails the
 * hash check by design — the bytes really are different. Their names, though, usually still
 * carry the title. This matcher normalises both sides and compares them as text.
 *
 * A text match is materially weaker evidence than a hash match, and the rest of the app
 * treats it that way: it is reported as [MatchStatus.FuzzyMatched], surfaced in the UI as
 * unverified, and left unticked so it cannot be renamed without the user opting in.
 *
 * Scoring every file against every title would be O(files × titles) with a Levenshtein
 * matrix in the inner loop, which is hopeless once several DATs are merged. Instead an
 * inverted token index narrows each query to the handful of titles sharing a word with it,
 * and only those are scored.
 */
class FuzzyTitleMatcher private constructor(
    private val titles: List<IndexedTitle>,
    private val postings: Map<String, IntArray>,
    private val threshold: Float,
) {

    val titleCount: Int get() = titles.size

    /**
     * @param fileName the local file name, extension included.
     * @param extension the local extension, used to pick which file of a multi-file release
     *   this could be. Multi-track discs cannot be identified by title alone.
     */
    fun match(fileName: String, extension: String): FuzzyResult {
        if (titles.isEmpty()) return FuzzyResult.NoMatch

        val variants = TitleNormalizer.fromFileName(fileName)
        if (variants.isEmpty()) return FuzzyResult.NoMatch

        val candidates = collectCandidates(variants)
        if (candidates.isEmpty()) return FuzzyResult.NoMatch

        var best = threshold
        var bestQuery = ""
        val leaders = mutableListOf<IndexedTitle>()

        for (position in candidates) {
            val title = titles[position]
            var score = 0f
            var query = ""
            for (variant in variants) {
                // Sequel numbers are not near-misses. "Gran Turismo 5" is 93% similar to
                // "Gran Turismo 4" by edit distance and is emphatically not that game.
                if (variant.numbers != title.normalized.numbers) continue
                val candidateScore = StringSimilarity.similarity(variant, title.normalized, best)
                if (candidateScore > score) {
                    score = candidateScore
                    query = variant.text
                }
            }
            if (score == 0f) continue

            when {
                score > best + TIE_EPSILON -> {
                    best = score
                    bestQuery = query
                    leaders.clear()
                    leaders += title
                }
                abs(score - best) <= TIE_EPSILON && score >= threshold -> leaders += title
            }
        }

        if (leaders.isEmpty()) return FuzzyResult.NoMatch

        // A title is only usable if we can say which file inside the release it refers to.
        val entries = leaders.mapNotNull { title ->
            pickRom(title.game, extension)?.let { rom -> DatEntry(title.game, rom, title.game.source) }
        }
        if (entries.isEmpty()) return FuzzyResult.NoMatch

        val distinct = entries.distinctBy { it.officialFileName }
        return if (distinct.size == 1) {
            FuzzyResult.Matched(distinct.first(), best, bestQuery)
        } else {
            FuzzyResult.Ambiguous(distinct, best)
        }
    }

    /**
     * Titles worth scoring: those sharing at least one token with the query.
     *
     * Rare tokens are consulted first, since they carry the most information per unit of
     * work — "viewtiful" narrows to a handful of titles where "the" would pull in
     * thousands. Collection stops once the budget is spent, so a query made entirely of
     * common words costs no more than any other.
     */
    private fun collectCandidates(variants: List<NormalizedTitle>): Set<Int> {
        val tokens = variants
            .flatMapTo(mutableSetOf()) { it.tokens }
            .mapNotNull { token -> postings[token]?.let { token to it } }
            .sortedBy { it.second.size }

        val candidates = LinkedHashSet<Int>()
        for ((_, posting) in tokens) {
            // A token in nearly every title says nothing; skip it unless we have nothing.
            if (posting.size > MAX_POSTING_SIZE && candidates.isNotEmpty()) continue
            for (position in posting) {
                candidates += position
                if (candidates.size >= MAX_CANDIDATES) return candidates
            }
        }
        return candidates
    }

    /**
     * Which file of a release a local file corresponds to.
     *
     * Single-file releases are unambiguous. For a multi-track disc the extension has to
     * settle it, and if it cannot — two `.bin` tracks, say — the title is unusable, because
     * renaming to the wrong track would be worse than not renaming at all.
     */
    private fun pickRom(game: DatGame, extension: String): DatRom? {
        val usable = game.roms.filterNot { it.isDumpKnownBad }
        if (usable.isEmpty()) return null
        if (usable.size == 1) return usable.first()
        return usable
            .filter { it.name.substringAfterLast('.', "").equals(extension, ignoreCase = true) }
            .singleOrNull()
    }

    internal class IndexedTitle(val game: DatGame, val normalized: NormalizedTitle)

    companion object {
        /**
         * Default confidence to accept a text match. High enough that a near-exact name is
         * needed; anything looser starts matching sequels to each other.
         */
        const val DEFAULT_THRESHOLD = 0.85f

        /** Titles scored per file, whatever the size of the merged database. */
        private const val MAX_CANDIDATES = 2_000

        /** Postings longer than this are treated as stop-words. */
        private const val MAX_POSTING_SIZE = 5_000

        /** Scores within this of the best are treated as tied. */
        private const val TIE_EPSILON = 0.001f

        /**
         * Builds the title index. This walks every game in the database, so it is the
         * caller's job to build it lazily — a scan where every file hashes cleanly should
         * never pay for it.
         */
        fun build(index: DatIndex, threshold: Float = DEFAULT_THRESHOLD): FuzzyTitleMatcher {
            val titles = ArrayList<IndexedTitle>(index.gameCount)
            val seen = HashSet<String>(index.gameCount)
            val postings = HashMap<String, MutableList<Int>>()

            for (game in index.games) {
                if (game.roms.isEmpty()) continue
                val normalized = TitleNormalizer.fromDatTitle(game.name)
                if (!normalized.isUsable) continue
                // Merged DATs overlap, and the same release listed twice would look
                // ambiguous for no reason. Deduplicate on the release itself rather than on
                // the normalised text: "(USA)" and "(Europe)" normalise identically but are
                // genuinely different releases, and collapsing them would silently pick one.
                if (!seen.add(releaseKey(game))) continue

                val position = titles.size
                titles += IndexedTitle(game, normalized)
                for (token in normalized.tokens) {
                    postings.getOrPut(token) { mutableListOf() } += position
                }
            }

            return FuzzyTitleMatcher(
                titles = titles,
                postings = postings.mapValues { it.value.toIntArray() },
                threshold = threshold.coerceIn(0.5f, 1f),
            )
        }

        /** Identifies a release: its title plus the files it is made of. */
        private fun releaseKey(game: DatGame): String =
            game.roms.joinToString(separator = " ", prefix = "${game.name} ") { it.name }
    }
}
