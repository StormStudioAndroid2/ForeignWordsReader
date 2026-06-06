package com.example.myapplication.shared.processing

class BookIndexBuilder(
    private val chunkSize: Int = DefaultBookIndexChunkSize,
    private val globalFrequencyRepository: GlobalFrequencyRepository = EmptyGlobalFrequencyRepository,
    private val tfIdfScorer: TfIdfScorer = TfIdfScorer(),
    private val lemmaCandidateFilter: LemmaCandidateFilter = LemmaCandidateFilter(),
) {
    fun build(
        bookId: String,
        metadata: TextAnalysisMetadata,
        tokens: List<AnalyzedToken>,
        processedAtMillis: Long? = null,
    ): BookIndex =
        score(
            filterCandidates(
                buildCandidates(
                    bookId = bookId,
                    metadata = metadata,
                    tokens = tokens,
                    processedAtMillis = processedAtMillis,
                ),
            ),
        )

    fun buildCandidates(
        bookId: String,
        metadata: TextAnalysisMetadata,
        tokens: List<AnalyzedToken>,
        processedAtMillis: Long? = null,
    ): BookLemmaCandidateIndex {
        require(chunkSize > 0) { "chunkSize must be positive." }

        val lemmaAccumulators = linkedMapOf<String, LemmaAccumulator>()
        val chunkCounts = linkedMapOf<ChunkLemmaKey, Long>()
        var wordLikeTokenCount = 0L

        tokens.forEach { token ->
            val lemma = token.normalizedCountableLemma() ?: return@forEach
            val chunkId = wordLikeTokenCount / chunkSize
            wordLikeTokenCount += 1
            val accumulator = lemmaAccumulators.getOrPut(lemma) { LemmaAccumulator() }
            accumulator.add(upos = token.normalizedUpos())

            val chunkKey = ChunkLemmaKey(chunkId = chunkId, lemma = lemma)
            chunkCounts[chunkKey] = (chunkCounts[chunkKey] ?: 0L) + 1L
        }

        val globalFrequencies = globalFrequencyRepository.getZipfFrequencies(
            language = metadata.language,
            lemmas = lemmaAccumulators.keys,
        )

        return BookLemmaCandidateIndex(
            metadata = BookLemmaCandidateMetadata(
                bookId = bookId,
                language = metadata.language,
                nlpProvider = metadata.nlpProvider,
                udpipeVersion = metadata.providerVersion,
                modelId = metadata.modelId,
                modelVersion = metadata.modelVersion,
                indexVersion = metadata.indexVersion,
                tokenCount = wordLikeTokenCount,
                processedAtMillis = processedAtMillis,
            ),
            lemmaCandidates = lemmaAccumulators.map { (lemma, accumulator) ->
                BookLemmaCandidate(
                    bookId = bookId,
                    lemma = lemma,
                    totalCount = accumulator.totalCount,
                    globalFrequencyZipf = globalFrequencies[lemma],
                    uposCounts = accumulator.sortedUposCounts(),
                    dominantUpos = accumulator.dominantUpos(),
                    propnRatio = accumulator.propnRatio(),
                )
            },
            chunkLemmaCounts = chunkCounts.entries
                .map { (key, count) ->
                    BookChunkLemmaCount(
                        bookId = bookId,
                        chunkId = key.chunkId,
                        lemma = key.lemma,
                        localCount = count,
                    )
                },
        )
    }

    fun filterCandidates(candidateIndex: BookLemmaCandidateIndex): FilteredBookLemmaCandidates {
        val acceptedLemmas = candidateIndex.lemmaCandidates
            .filter(lemmaCandidateFilter::accepts)
            .mapTo(mutableSetOf()) { it.lemma }

        return FilteredBookLemmaCandidates(
            metadata = candidateIndex.metadata,
            lemmaCandidates = candidateIndex.lemmaCandidates.filter { it.lemma in acceptedLemmas },
            chunkLemmaCounts = candidateIndex.chunkLemmaCounts.filter { it.lemma in acceptedLemmas },
        )
    }

    fun score(filteredCandidates: FilteredBookLemmaCandidates): BookIndex {
        val metadata = filteredCandidates.metadata
        val lemmaCounts = filteredCandidates.lemmaCandidates
            .map { candidate ->
                BookLemmaCount(
                    bookId = candidate.bookId,
                    lemma = candidate.lemma,
                    totalCount = candidate.totalCount,
                    globalFrequencyZipf = candidate.globalFrequencyZipf,
                    tfIdfScore = tfIdfScorer.score(
                        bookCount = candidate.totalCount,
                        globalZipfFrequency = candidate.globalFrequencyZipf,
                    ),
                )
            }
            .sortedWith(
                compareByDescending<BookLemmaCount> { it.tfIdfScore }
                    .thenByDescending { it.totalCount }
                    .thenBy { it.lemma },
            )

        val chunkLemmaCounts = filteredCandidates.chunkLemmaCounts
            .sortedWith(compareBy<BookChunkLemmaCount> { it.chunkId }.thenBy { it.lemma })

        return BookIndex(
            metadata = BookIndexMetadata(
                bookId = metadata.bookId,
                language = metadata.language,
                nlpProvider = metadata.nlpProvider,
                udpipeVersion = metadata.udpipeVersion,
                modelId = metadata.modelId,
                modelVersion = metadata.modelVersion,
                indexVersion = metadata.indexVersion,
                tokenCount = metadata.tokenCount,
                uniqueLemmaCount = lemmaCounts.size.toLong(),
                savedIndexSizeBytes = estimateIndexSizeBytes(lemmaCounts, chunkLemmaCounts),
                processedAtMillis = metadata.processedAtMillis,
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

    private fun AnalyzedToken.normalizedUpos(): String =
        upos.trim().ifBlank { UnknownUpos }

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

    private class LemmaAccumulator {
        private val uposCounts = linkedMapOf<String, Long>()

        val totalCount: Long
            get() = uposCounts.values.sum()

        fun add(upos: String) {
            uposCounts[upos] = (uposCounts[upos] ?: 0L) + 1L
        }

        fun dominantUpos(): String =
            sortedUposEntries().firstOrNull()?.key ?: UnknownUpos

        fun propnRatio(): Double {
            val total = totalCount
            if (total <= 0L) {
                return 0.0
            }
            return (uposCounts["PROPN"] ?: 0L).toDouble() / total.toDouble()
        }

        fun sortedUposCounts(): Map<String, Long> =
            sortedUposEntries().associate { (upos, count) -> upos to count }

        private fun sortedUposEntries(): List<Map.Entry<String, Long>> =
            uposCounts.entries.sortedWith(
                compareByDescending<Map.Entry<String, Long>> { it.value }
                    .thenBy { it.key },
            )
    }

    private companion object {
        const val LongByteSize = 8L
        const val DoubleByteSize = 8L
        const val UnknownUpos = "_"
    }
}

class LemmaCandidateFilter {
    fun accepts(candidate: BookLemmaCandidate): Boolean {
        val normalizedLemma = normalizeApostrophes(candidate.lemma)
        val lettersOnly = normalizedLemma.filterNot { it == Apostrophe }

        return candidate.dominantUpos in AllowedDominantUpos &&
            normalizedLemma !in ContractionFragments &&
            normalizedLemma.none { it.isDigit() } &&
            normalizedLemma.length >= MinLemmaLength &&
            candidate.propnRatio < MaxPropnRatio &&
            lettersOnly.isNotEmpty() &&
            lettersOnly.all { it.isLetter() } &&
            (candidate.globalFrequencyZipf != null || candidate.totalCount >= MinCountWithoutGlobalFrequency)
    }

    private fun normalizeApostrophes(value: String): String =
        buildString(capacity = value.length) {
            value.forEach { char ->
                append(if (char in ApostropheVariants) Apostrophe else char)
            }
        }

    private companion object {
        val AllowedDominantUpos = setOf("NOUN", "VERB", "ADJ", "ADV")
        val ContractionFragments = setOf("ca", "wo", "n't", "'s", "'re", "'ve", "'ll", "'d", "'m")
        val ApostropheVariants = setOf('\u2018', '\u2019', '\u02BC', '\uFF07', '`')
        const val Apostrophe = '\''
        const val MinLemmaLength = 3
        const val MaxPropnRatio = 0.4
        const val MinCountWithoutGlobalFrequency = 5L
    }
}
