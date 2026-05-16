package com.example.myapplication.shared.processing

import kotlin.math.ln

class TfIdfScorer(
    private val neutralZipf: Double = NeutralZipf,
    private val rarityScale: Double = RarityScale,
    private val minRarityWeight: Double = MinRarityWeight,
    private val maxRarityWeight: Double = MaxRarityWeight,
) {
    fun score(
        bookCount: Long,
        globalZipfFrequency: Double?,
    ): Double {
        if (bookCount <= 0L) {
            return 0.0
        }

        return ln(bookCount.toDouble() + 1.0) * rarityWeight(globalZipfFrequency)
    }

    fun rarityWeight(globalZipfFrequency: Double?): Double {
        if (globalZipfFrequency == null) {
            return NeutralRarityWeight
        }

        return (NeutralRarityWeight + (neutralZipf - globalZipfFrequency) / rarityScale)
            .coerceIn(minRarityWeight, maxRarityWeight)
    }

    private companion object {
        const val NeutralZipf = 4.0
        const val RarityScale = 2.0
        const val NeutralRarityWeight = 1.0
        const val MinRarityWeight = 0.15
        const val MaxRarityWeight = 2.5
    }
}

fun normalizeLemmaKey(value: String): String? {
    val normalized = value.trim().lowercase()
    return normalized
        .takeUnless { it.isBlank() || it == "_" }
        ?.takeUnless { lemma -> lemma.all { !it.isLetterOrDigit() } }
}

