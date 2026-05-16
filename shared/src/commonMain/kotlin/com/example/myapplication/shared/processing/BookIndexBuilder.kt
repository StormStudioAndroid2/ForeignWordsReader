package com.example.myapplication.shared.processing

class BookIndexBuilder(
    private val chunkSize: Int = DefaultBookIndexChunkSize,
    private val globalFrequencyRepository: GlobalFrequencyRepository = EmptyGlobalFrequencyRepository,
    private val tfIdfScorer: TfIdfScorer = TfIdfScorer(),
) {
    fun build(
        bookId: String,
        metadata: TextAnalysisMetadata,
        tokens: List<AnalyzedToken>,
        processedAtMillis: Long? = null,
    ): BookIndex {
        require(chunkSize > 0) { "chunkSize must be positive." }

        val globalCounts = linkedMapOf<String, Long>()
        val chunkCounts = linkedMapOf<ChunkLemmaKey, Long>()
        var wordLikeTokenCount = 0L

        tokens.forEach { token ->
            val lemma = token.normalizedCountableLemma() ?: return@forEach
            val chunkId = wordLikeTokenCount / chunkSize
            wordLikeTokenCount += 1
            globalCounts[lemma] = (globalCounts[lemma] ?: 0L) + 1L

            val chunkKey = ChunkLemmaKey(chunkId = chunkId, lemma = lemma)
            chunkCounts[chunkKey] = (chunkCounts[chunkKey] ?: 0L) + 1L
        }

        val globalFrequencies = globalFrequencyRepository.getZipfFrequencies(
            language = metadata.language,
            lemmas = globalCounts.keys,
        )

        val lemmaCounts = globalCounts.entries
            .map { (lemma, count) ->
                val globalFrequencyZipf = globalFrequencies[lemma]
                BookLemmaCount(
                    bookId = bookId,
                    lemma = lemma,
                    totalCount = count,
                    globalFrequencyZipf = globalFrequencyZipf,
                    tfIdfScore = tfIdfScorer.score(
                        bookCount = count,
                        globalZipfFrequency = globalFrequencyZipf,
                    ),
                )
            }
            .sortedWith(
                compareByDescending<BookLemmaCount> { it.tfIdfScore }
                    .thenByDescending { it.totalCount }
                    .thenBy { it.lemma },
            )

        val chunkLemmaCounts = chunkCounts.entries
            .map { (key, count) ->
                BookChunkLemmaCount(
                    bookId = bookId,
                    chunkId = key.chunkId,
                    lemma = key.lemma,
                    localCount = count,
                )
            }
            .sortedWith(compareBy<BookChunkLemmaCount> { it.chunkId }.thenBy { it.lemma })

        return BookIndex(
            metadata = BookIndexMetadata(
                bookId = bookId,
                language = metadata.language,
                nlpProvider = metadata.nlpProvider,
                udpipeVersion = metadata.providerVersion,
                modelId = metadata.modelId,
                modelVersion = metadata.modelVersion,
                indexVersion = metadata.indexVersion,
                tokenCount = wordLikeTokenCount,
                uniqueLemmaCount = lemmaCounts.size.toLong(),
                savedIndexSizeBytes = estimateIndexSizeBytes(lemmaCounts, chunkLemmaCounts),
                processedAtMillis = processedAtMillis,
            ),
            lemmaCounts = lemmaCounts,
            chunkLemmaCounts = chunkLemmaCounts,
        )
    }

    private fun AnalyzedToken.normalizedCountableLemma(): String? {
        if (tokenType != TokenType.Word || upos == "PUNCT" || upos == "SYM") {
            return null
        }

        return normalizeLemmaKey(lemma)
    }

    private fun estimateIndexSizeBytes(
        lemmaCounts: List<BookLemmaCount>,
        chunkLemmaCounts: List<BookChunkLemmaCount>,
    ): Long {
        val globalBytes = lemmaCounts.sumOf {
            it.lemma.length.toLong() + LongByteSize + DoubleByteSize + DoubleByteSize
        }
        val chunkBytes = chunkLemmaCounts.sumOf { it.lemma.length.toLong() + LongByteSize + LongByteSize }
        return globalBytes + chunkBytes
    }

    private data class ChunkLemmaKey(
        val chunkId: Long,
        val lemma: String,
    )

    private companion object {
        const val LongByteSize = 8L
        const val DoubleByteSize = 8L
    }
}
