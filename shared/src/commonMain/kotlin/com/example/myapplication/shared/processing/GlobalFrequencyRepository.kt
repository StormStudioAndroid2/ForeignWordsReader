package com.example.myapplication.shared.processing

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

interface GlobalFrequencyRepository {
    fun getZipfFrequencies(
        language: String,
        lemmas: Set<String>,
    ): Map<String, Double>
}

object EmptyGlobalFrequencyRepository : GlobalFrequencyRepository {
    override fun getZipfFrequencies(
        language: String,
        lemmas: Set<String>,
    ): Map<String, Double> = emptyMap()
}

class SqlDelightGlobalFrequencyRepository(
    private val driver: SqlDriver,
    private val batchSize: Int = DefaultBatchSize,
) : GlobalFrequencyRepository {
    init {
        require(batchSize > 0) { "batchSize must be positive." }
    }

    override fun getZipfFrequencies(
        language: String,
        lemmas: Set<String>,
    ): Map<String, Double> {
        val normalizedLemmas = lemmas
            .mapNotNull(::normalizeLemmaKey)
            .distinct()

        if (normalizedLemmas.isEmpty()) {
            return emptyMap()
        }

        val frequencies = linkedMapOf<String, Double>()
        normalizedLemmas.chunked(batchSize).forEach { batch ->
            val placeholders = batch.joinToString(separator = ",") { "?" }
            val sql = """
                SELECT lemma, zipf_frequency
                FROM global_lemma_frequency
                WHERE language = ?
                  AND lemma IN ($placeholders)
            """.trimIndent()

            driver.executeQuery(
                identifier = null,
                sql = sql,
                mapper = { cursor ->
                    while (cursor.next().value) {
                        val lemma = cursor.getString(0)
                        val zipf = cursor.getDouble(1)
                        if (lemma != null && zipf != null) {
                            frequencies[lemma] = zipf
                        }
                    }
                    QueryResult.Value(Unit)
                },
                parameters = batch.size + 1,
                binders = {
                    bindString(0, language)
                    batch.forEachIndexed { index, lemma ->
                        bindString(index + 1, lemma)
                    }
                },
            ).value
        }

        return frequencies
    }

    private companion object {
        const val DefaultBatchSize = 250
    }
}

