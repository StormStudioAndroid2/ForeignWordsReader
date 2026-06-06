package com.example.myapplication.shared.main

import com.example.myapplication.shared.processing.BookProcessingState

data class BookItem(
    val id: String,
    val uriString: String,
    val title: String,
    val author: String,
    val coverUriString: String?,
    val lastOpenedAtMillis: Long,
    val processingState: BookProcessingState = BookProcessingState.NotStarted,
    val processingTokenCount: Long = 0L,
    val processingErrorMessage: String? = null,
)
