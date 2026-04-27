package com.example.myapplication.shared.reader

data class ReaderSearchPage(
    val results: List<ReaderSearchResultItem>,
)

data class ReaderSearchResultItem(
    val id: String,
    val locatorJson: String,
    val title: String,
    val before: String,
    val highlight: String,
    val after: String,
    val progression: Double,
    val position: Int,
)
