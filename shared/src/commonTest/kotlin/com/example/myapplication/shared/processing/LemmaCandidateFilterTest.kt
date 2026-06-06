package com.example.myapplication.shared.processing

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LemmaCandidateFilterTest {
    private val filter = LemmaCandidateFilter()

    @Test
    fun acceptsAllowedDominantUposValues() {
        listOf("NOUN", "VERB", "ADJ", "ADV").forEach { upos ->
            assertTrue(filter.accepts(candidate(dominantUpos = upos)), "Expected $upos to be accepted.")
        }
    }

    @Test
    fun rejectsDisallowedDominantUposValues() {
        listOf("DET", "ADP", "AUX", "PROPN", "CCONJ", "PART", "_").forEach { upos ->
            assertFalse(filter.accepts(candidate(dominantUpos = upos)), "Expected $upos to be rejected.")
        }
    }

    @Test
    fun rejectsContractionFragments() {
        listOf("ca", "wo", "n't", "'s", "'re", "'ve", "'ll", "'d", "'m").forEach { lemma ->
            assertFalse(filter.accepts(candidate(lemma = lemma)), "Expected $lemma to be rejected.")
        }
    }

    @Test
    fun rejectsLemmasContainingDigits() {
        listOf("book2", "b2b").forEach { lemma ->
            assertFalse(filter.accepts(candidate(lemma = lemma)), "Expected $lemma to be rejected.")
        }
    }

    @Test
    fun rejectsLemmasShorterThanThreeAfterApostropheNormalization() {
        assertFalse(filter.accepts(candidate(lemma = "go")))
    }

    @Test
    fun rejectsPropnRatioAtOrAboveThreshold() {
        assertTrue(filter.accepts(candidate(propnRatio = 0.399)))
        assertFalse(filter.accepts(candidate(propnRatio = 0.4)))
        assertFalse(filter.accepts(candidate(propnRatio = 0.5)))
    }

    @Test
    fun rejectsNonLettersAfterApostropheNormalizationAndStripping() {
        listOf("rock-star", "co_op", "word.").forEach { lemma ->
            assertFalse(filter.accepts(candidate(lemma = lemma)), "Expected $lemma to be rejected.")
        }
    }

    @Test
    fun acceptsApostropheVariantsWhenRemainingCharactersAreLetters() {
        listOf("o'clock", "o\u2019clock", "o\u02BCclock", "o\uFF07clock").forEach { lemma ->
            assertTrue(filter.accepts(candidate(lemma = lemma)), "Expected $lemma to be accepted.")
        }
    }

    @Test
    fun rejectsMissingZipfForLowCountsOnly() {
        assertFalse(filter.accepts(candidate(totalCount = 4L, zipf = null)))
        assertTrue(filter.accepts(candidate(totalCount = 5L, zipf = null)))
        assertTrue(filter.accepts(candidate(totalCount = 1L, zipf = 4.0)))
    }

    private fun candidate(
        lemma: String = "book",
        dominantUpos: String = "NOUN",
        totalCount: Long = 1L,
        zipf: Double? = 4.0,
        propnRatio: Double = 0.0,
    ): BookLemmaCandidate =
        BookLemmaCandidate(
            bookId = TestBook.id,
            lemma = lemma,
            totalCount = totalCount,
            globalFrequencyZipf = zipf,
            uposCounts = mapOf(dominantUpos to totalCount),
            dominantUpos = dominantUpos,
            propnRatio = propnRatio,
        )
}
