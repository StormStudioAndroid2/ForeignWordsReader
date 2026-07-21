package com.example.myapplication.shared.reader

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value

class DefaultWordsComponent(
    private val gateway: ReaderWordsGateway,
) : WordsComponent {

    private val mutableModel = MutableValue(WordsComponent.Model())
    private var requestId: Long = 0

    override val model: Value<WordsComponent.Model> = mutableModel

    override fun onOpenRequested() {
        requestId += 1
        val currentRequestId = requestId
        mutableModel.value = mutableModel.value.copy(
            isVisible = true,
            status = WordsComponent.Status.Loading,
            items = emptyList(),
            errorMessage = null,
        )

        gateway.loadWords(
            onResult = { items ->
                if (currentRequestId != requestId) return@loadWords
                mutableModel.value = mutableModel.value.copy(
                    isVisible = true,
                    status = if (items.isEmpty()) {
                        WordsComponent.Status.Empty
                    } else {
                        WordsComponent.Status.Loaded
                    },
                    items = items,
                    errorMessage = null,
                )
            },
            onError = { message ->
                if (currentRequestId != requestId) return@loadWords
                mutableModel.value = mutableModel.value.copy(
                    isVisible = true,
                    status = WordsComponent.Status.Error,
                    items = emptyList(),
                    errorMessage = message,
                )
            },
        )
    }

    override fun onDismissRequested() {
        requestId += 1
        mutableModel.value = mutableModel.value.copy(isVisible = false)
    }
}
