package com.example.myapplication.shared.reader

import com.example.myapplication.shared.processing.BookLemmaCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultWordsComponentTest {

    @Test
    fun openLoadsWords() {
        val gateway = FakeReaderWordsGateway()
        val component = DefaultWordsComponent(gateway)
        val items = listOf(
            ReaderWordItem(
                lemma = "remember",
                displayWord = "remembered",
                totalCount = 21,
                surfaceWords = listOf("remembered", "remember"),
            ),
        )

        component.onOpenRequested()
        assertEquals(WordsComponent.Status.Loading, component.model.value.status)
        assertTrue(component.model.value.isVisible)

        gateway.complete(items)

        assertEquals(WordsComponent.Status.Loaded, component.model.value.status)
        assertEquals(items, component.model.value.items)
        assertTrue(component.model.value.isVisible)
    }

    @Test
    fun dismissHidesPanel() {
        val component = DefaultWordsComponent(
            ImmediateReaderWordsGateway(
                listOf(
                    ReaderWordItem(
                        lemma = "letter",
                        displayWord = "letters",
                        totalCount = 19,
                        surfaceWords = listOf("letters"),
                    ),
                ),
            ),
        )

        component.onOpenRequested()
        component.onDismissRequested()

        assertFalse(component.model.value.isVisible)
        assertEquals(WordsComponent.Status.Loaded, component.model.value.status)
    }

    @Test
    fun emptyResultUsesEmptyState() {
        val component = DefaultWordsComponent(ImmediateReaderWordsGateway(emptyList()))

        component.onOpenRequested()

        assertEquals(WordsComponent.Status.Empty, component.model.value.status)
        assertEquals(emptyList(), component.model.value.items)
    }

    @Test
    fun gatewayErrorUsesErrorState() {
        val component = DefaultWordsComponent(ErrorReaderWordsGateway("Words are not ready."))

        component.onOpenRequested()

        assertEquals(WordsComponent.Status.Error, component.model.value.status)
        assertEquals("Words are not ready.", component.model.value.errorMessage)
        assertEquals(emptyList(), component.model.value.items)
    }

    @Test
    fun lemmaCountMapsDisplayWordFromSurfaceWords() {
        val item = BookLemmaCount(
            bookId = "book-1",
            lemma = "remember",
            totalCount = 17,
            globalFrequencyZipf = 4.1,
            tfIdfScore = 2.0,
            surfaceWords = listOf("remembered", "remember"),
        ).toReaderWordItem()

        assertEquals("remembered", item.displayWord)
        assertEquals("remember", item.lemma)
        assertEquals(17, item.totalCount)
        assertEquals(listOf("remembered", "remember"), item.surfaceWords)
    }

    @Test
    fun lemmaCountFallsBackToLemmaWhenSurfaceWordsAreMissing() {
        val item = BookLemmaCount(
            bookId = "book-1",
            lemma = "letter",
            totalCount = 13,
            globalFrequencyZipf = null,
            tfIdfScore = 1.4,
            surfaceWords = emptyList(),
        ).toReaderWordItem()

        assertEquals("letter", item.displayWord)
        assertEquals(emptyList(), item.surfaceWords)
    }

    @Test
    fun loadedItemsPreserveGatewayOrdering() {
        val items = listOf(
            ReaderWordItem("letter", "letters", 31, listOf("letters")),
            ReaderWordItem("remember", "remembered", 24, listOf("remembered")),
            ReaderWordItem("window", "window", 24, listOf("window")),
        )
        val component = DefaultWordsComponent(ImmediateReaderWordsGateway(items))

        component.onOpenRequested()

        assertEquals(items, component.model.value.items)
    }

    private class ImmediateReaderWordsGateway(
        private val items: List<ReaderWordItem>,
    ) : ReaderWordsGateway {
        override fun loadWords(
            onResult: (List<ReaderWordItem>) -> Unit,
            onError: (String) -> Unit,
        ) {
            onResult(items)
        }
    }

    private class ErrorReaderWordsGateway(
        private val message: String,
    ) : ReaderWordsGateway {
        override fun loadWords(
            onResult: (List<ReaderWordItem>) -> Unit,
            onError: (String) -> Unit,
        ) {
            onError(message)
        }
    }

    private class FakeReaderWordsGateway : ReaderWordsGateway {
        private var onResult: ((List<ReaderWordItem>) -> Unit)? = null
        private var onError: ((String) -> Unit)? = null

        override fun loadWords(
            onResult: (List<ReaderWordItem>) -> Unit,
            onError: (String) -> Unit,
        ) {
            this.onResult = onResult
            this.onError = onError
        }

        fun complete(items: List<ReaderWordItem>) {
            onResult?.invoke(items)
        }
    }
}
