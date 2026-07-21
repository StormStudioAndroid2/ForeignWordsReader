package com.example.myapplication.shared.reader

interface ReaderWordsGateway {

    fun loadWords(
        onResult: (List<ReaderWordItem>) -> Unit,
        onError: (String) -> Unit,
    )
}

object EmptyReaderWordsGateway : ReaderWordsGateway {
    override fun loadWords(
        onResult: (List<ReaderWordItem>) -> Unit,
        onError: (String) -> Unit,
    ) {
        onResult(emptyList())
    }
}
