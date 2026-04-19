package com.example.myapplication.shared.main

data class BookItem(
    val id: String,
    val uriString: String,
    val title: String,
    val author: String,
    val coverUriString: String?,
    val lastOpenedAtMillis: Long,
)
