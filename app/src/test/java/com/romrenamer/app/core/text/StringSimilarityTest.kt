package com.romrenamer.app.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StringSimilarityTest {

    @Test
    fun `edit distance counts insertions, deletions and substitutions`() {
        assertEquals(0, StringSimilarity.levenshtein("kitten", "kitten"))
        assertEquals(3, StringSimilarity.levenshtein("kitten", "sitting"))
        assertEquals(4, StringSimilarity.levenshtein("", "abcd"))
        assertEquals(4, StringSimilarity.levenshtein("abcd", ""))
    }

    @Test
    fun `edit distance is symmetric`() {
        assertEquals(
            StringSimilarity.levenshtein("viewtiful joe", "viewtifull joe"),
            StringSimilarity.levenshtein("viewtifull joe", "viewtiful joe"),
        )
    }

    @Test
    fun `the distance bound short-circuits without changing decisions`() {
        // Anything over the bound is reported as bound + 1, which is all a threshold needs.
        assertEquals(3, StringSimilarity.levenshtein("kitten", "sitting", maxDistance = 5))
        assertTrue(StringSimilarity.levenshtein("kitten", "sitting", maxDistance = 1) > 1)
        assertTrue(StringSimilarity.levenshtein("short", "a much longer string", maxDistance = 2) > 2)
    }

    @Test
    fun `ratio is 1 for identical strings and 0 for total mismatch`() {
        assertEquals(1f, StringSimilarity.levenshteinRatio("mario", "mario"), 0.0001f)
        assertEquals(1f, StringSimilarity.levenshteinRatio("", ""), 0.0001f)
        assertEquals(0f, StringSimilarity.levenshteinRatio("abcd", "wxyz"), 0.0001f)
    }

    @Test
    fun `a one-character typo stays close to 1`() {
        val ratio = StringSimilarity.levenshteinRatio("viewtiful joe", "viewtifull joe")

        assertTrue("ratio was $ratio", ratio > 0.9f)
    }

    @Test
    fun `jaccard ignores word order`() {
        val a = setOf("viewtiful", "joe")
        val b = setOf("joe", "viewtiful")

        assertEquals(1f, StringSimilarity.jaccard(a, b), 0.0001f)
    }

    @Test
    fun `jaccard measures partial overlap`() {
        val a = setOf("gran", "turismo", "4")
        val b = setOf("gran", "turismo", "4", "prologue")

        assertEquals(0.75f, StringSimilarity.jaccard(a, b), 0.0001f)
        assertEquals(0f, StringSimilarity.jaccard(a, setOf("tekken")), 0.0001f)
        assertEquals(0f, StringSimilarity.jaccard(emptySet(), a), 0.0001f)
    }

    @Test
    fun `token overlap rescues a reordered title that edit distance would reject`() {
        val query = TitleNormalizer.fromFileName("Joe, Viewtiful.iso").first()
        val title = TitleNormalizer.fromDatTitle("Viewtiful Joe (USA)")

        assertTrue(StringSimilarity.levenshteinRatio(query.text, title.text) < 0.6f)
        assertEquals(1f, StringSimilarity.similarity(query, title), 0.0001f)
    }

    @Test
    fun `a trailing tag costs enough similarity to matter`() {
        val tagged = NormalizedTitle(
            "viewtiful joe red hot rumble memorypsp",
            setOf("viewtiful", "joe", "red", "hot", "rumble", "memorypsp"),
        )
        val official = TitleNormalizer.fromDatTitle("Viewtiful Joe - Red Hot Rumble (USA)")

        // Under the default threshold, which is exactly why tag stripping is needed.
        assertTrue(StringSimilarity.similarity(tagged, official) < 0.85f)
    }
}
