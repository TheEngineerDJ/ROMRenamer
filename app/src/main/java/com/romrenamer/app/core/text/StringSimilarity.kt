package com.romrenamer.app.core.text

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * Text similarity measures used by the fuzzy title fallback.
 *
 * Two are combined because they fail in different places. Levenshtein catches typos,
 * punctuation drift, and missing characters but punishes reordering harshly; Jaccard over
 * token sets ignores word order entirely — "Joe, Viewtiful" against "Viewtiful Joe" — but
 * cannot see inside a word. Taking the better of the two lets either one carry a match.
 */
object StringSimilarity {

    /**
     * Levenshtein edit distance, with an early exit.
     *
     * [maxDistance] bounds the work: once every cell in a row exceeds it, no completion of
     * the matrix can come back under, so the function returns `maxDistance + 1` and stops.
     * The fallback scores one file against thousands of candidate titles, and this bound is
     * what keeps that affordable.
     */
    fun levenshtein(a: String, b: String, maxDistance: Int = Int.MAX_VALUE): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        if (abs(a.length - b.length) > maxDistance) return maxDistance + 1

        // Iterate over the shorter string so the rows stay small.
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a

        var previous = IntArray(shorter.length + 1) { it }
        var current = IntArray(shorter.length + 1)

        for (j in 1..longer.length) {
            current[0] = j
            var rowMin = current[0]
            for (i in 1..shorter.length) {
                val substitution = previous[i - 1] + if (shorter[i - 1] == longer[j - 1]) 0 else 1
                current[i] = minOf(current[i - 1] + 1, previous[i] + 1, substitution)
                if (current[i] < rowMin) rowMin = current[i]
            }
            if (rowMin > maxDistance) return maxDistance + 1

            val swap = previous
            previous = current
            current = swap
        }
        return previous[shorter.length]
    }

    /**
     * Edit distance as a 0..1 similarity.
     *
     * [minRatio] is a hint, not a filter: supplying the caller's threshold lets the distance
     * computation bail out early. Results below it are still ordered sensibly but should be
     * treated only as "under the threshold".
     */
    fun levenshteinRatio(a: String, b: String, minRatio: Float = 0f): Float {
        val longest = max(a.length, b.length)
        if (longest == 0) return 1f
        val budget = floor((1f - minRatio) * longest).toInt()
        val distance = levenshtein(a, b, budget)
        return (1f - distance.toFloat() / longest).coerceIn(0f, 1f)
    }

    /** Overlap of two token sets: shared tokens over total distinct tokens. */
    fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val smaller = if (a.size <= b.size) a else b
        val larger = if (a.size <= b.size) b else a
        var shared = 0
        for (token in smaller) if (token in larger) shared++
        if (shared == 0) return 0f
        return shared.toFloat() / (a.size + b.size - shared)
    }

    /** The combined score the fuzzy matcher thresholds on. */
    fun similarity(a: NormalizedTitle, b: NormalizedTitle, minRatio: Float = 0f): Float =
        max(jaccard(a.tokens, b.tokens), levenshteinRatio(a.text, b.text, minRatio))
}
