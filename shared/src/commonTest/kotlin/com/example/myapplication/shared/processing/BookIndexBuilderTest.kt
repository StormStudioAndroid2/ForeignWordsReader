package com.example.myapplication.shared.processing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.measureTime

class BookIndexBuilderTest {
    private val metadata = TextAnalysisMetadata(
        language = DefaultAnalysisLanguage,
        nlpProvider = DefaultAnalysisProvider,
        providerVersion = "1.3.1",
        modelId = DefaultAnalysisModelId,
        modelVersion = DefaultAnalysisModelVersion,
        indexVersion = DefaultAnalysisIndexVersion,
    )

    @Test
    fun buildsGlobalAndChunkLemmaCounts() {
        val tokens = List(801) { index ->
            token(lemma = if (index % 2 == 0) "read" else "book")
        }

        val index = BookIndexBuilder(chunkSize = 800).build(
            bookId = "book-1",
            metadata = metadata,
            tokens = tokens,
        )

        assertEquals(401, index.lemmaCounts.first { it.lemma == "read" }.totalCount)
        assertEquals(400, index.lemmaCounts.first { it.lemma == "book" }.totalCount)
        assertEquals(2, index.chunkLemmaCounts.map { it.chunkId }.distinct().size)
        assertEquals(1, index.chunkLemmaCounts.first { it.chunkId == 1L }.localCount)
    }

    @Test
    fun excludesPunctuationAndSymbolsFromCounts() {
        val index = BookIndexBuilder().build(
            bookId = "book-1",
            metadata = metadata,
            tokens = List(5) { token(lemma = "child", upos = "NOUN") } +
                listOf(
                    token(lemma = ".", upos = "PUNCT", tokenType = TokenType.Punctuation),
                    token(lemma = "$", upos = "SYM", tokenType = TokenType.Symbol),
                ),
        )

        assertEquals(listOf("child"), index.lemmaCounts.map { it.lemma })
        assertEquals(5, index.lemmaCounts.first { it.lemma == "child" }.totalCount)
        assertFalse(index.chunkLemmaCounts.any { it.lemma == "." || it.lemma == "$" })
    }

    @Test
    fun buildsLemmaCandidatesBeforeFilteringAndScoring() {
        val frequencyRepository = FakeGlobalFrequencyRepository(mapOf("read" to 3.5))
        val candidateIndex = BookIndexBuilder(globalFrequencyRepository = frequencyRepository).buildCandidates(
            bookId = "book-1",
            metadata = metadata,
            tokens = listOf(
                token(lemma = "Read", upos = "VERB"),
                token(lemma = "read", upos = "NOUN"),
                token(lemma = "read", upos = "VERB"),
                token(lemma = "paris", upos = "PROPN"),
            ),
            processedAtMillis = 2_000L,
        )

        val read = candidateIndex.lemmaCandidates.first { it.lemma == "read" }
        assertEquals(3L, read.totalCount)
        assertEquals("VERB", read.dominantUpos)
        assertEquals(mapOf("VERB" to 2L, "NOUN" to 1L), read.uposCounts)
        assertEquals(0.0, read.propnRatio)
        assertEquals(3.5, read.globalFrequencyZipf)
        assertEquals(4L, candidateIndex.metadata.tokenCount)
        assertEquals(2_000L, candidateIndex.metadata.processedAtMillis)
    }

    @Test
    fun addsTfIdfLikeScoreWithoutChangingExistingTfCounts() {
        val frequencyRepository = FakeGlobalFrequencyRepository(
            mapOf(
                "thing" to 6.5,
                "whale" to 2.5,
                "harpoon" to 0.8,
                "the" to 7.5,
                "and" to 7.8,
            ),
        )
        val tokens =
            List(30) { token(lemma = "thing", upos = "NOUN") } +
                List(5) { token(lemma = "whale", upos = "NOUN") } +
                List(3) { token(lemma = "harpoon", upos = "NOUN") } +
                List(80) { token(lemma = "the", upos = "DET") } +
                List(100) { token(lemma = "and", upos = "CCONJ") }

        val index = BookIndexBuilder(
            globalFrequencyRepository = frequencyRepository,
        ).build(
            bookId = "book-1",
            metadata = metadata,
            tokens = tokens,
        )

        assertFalse(index.lemmaCounts.any { it.lemma == "the" || it.lemma == "and" })
        assertEquals(listOf("harpoon", "whale"), index.lemmaCounts.take(2).map { it.lemma })
        assertEquals(0.8, index.lemmaCounts.first { it.lemma == "harpoon" }.globalFrequencyZipf)
        assertTrue(index.lemmaCounts.first { it.lemma == "harpoon" }.tfIdfScore > index.lemmaCounts.first { it.lemma == "thing" }.tfIdfScore)
    }

    @Test
    fun benchmarkSeveralThousandWordLikeTokens() {
        val tokens = List(5_000) { index ->
            token(lemma = alphaLemma(index % 250))
        }

        lateinit var index: BookIndex
        val elapsed = measureTime {
            index = BookIndexBuilder().build(
                bookId = "book-1",
                metadata = metadata,
                tokens = tokens,
            )
        }

        println(
            "BookIndexBuilder benchmark tokens=${index.metadata.tokenCount} " +
                "uniqueLemmas=${index.metadata.uniqueLemmaCount} elapsed=$elapsed",
        )
        assertEquals(5_000, index.metadata.tokenCount)
        assertEquals(250, index.metadata.uniqueLemmaCount)
    }

    private fun token(
        lemma: String,
        upos: String = "NOUN",
        tokenType: TokenType = TokenType.Word,
    ): AnalyzedToken =
        AnalyzedToken(
            sectionId = "section-1",
            tokenOrder = 0,
            surface = lemma,
            lemma = lemma,
            upos = upos,
            tokenType = tokenType,
        )

    private fun alphaLemma(index: Int): String {
        val first = 'a' + (index / 26)
        val second = 'a' + (index % 26)
        return "lemma$first$second"
    }

    private class FakeGlobalFrequencyRepository(
        private val frequencies: Map<String, Double>,
    ) : GlobalFrequencyRepository {
        override fun getZipfFrequencies(
            language: String,
            lemmas: Set<String>,
        ): Map<String, Double> =
            lemmas.mapNotNull { lemma -> frequencies[lemma]?.let { zipf -> lemma to zipf } }.toMap()
    }
}
