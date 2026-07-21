package com.example.myapplication.shared.reader

import com.example.myapplication.shared.processing.BookLemmaCount

data class ReaderWordItem(
    val lemma: String,
    val displayWord: String,
    val totalCount: Long,
    val surfaceWords: List<String>,
)

fun BookLemmaCount.toReaderWordItem(): ReaderWordItem {
    val normalizedSurfaceWords = surfaceWords
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    return ReaderWordItem(
        lemma = lemma,
        displayWord = normalizedSurfaceWords.firstOrNull() ?: lemma,
        totalCount = totalCount,
        surfaceWords = normalizedSurfaceWords,
    )
}
