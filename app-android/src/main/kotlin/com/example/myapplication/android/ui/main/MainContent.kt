package com.example.myapplication.android.ui.main

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.example.myapplication.android.ui.reader.LoadingReaderState
import com.example.myapplication.shared.main.BookItem
import com.example.myapplication.shared.main.MainComponent
import com.example.myapplication.shared.processing.BookProcessingState

@Composable
fun AndroidMainContent(
    component: MainComponent,
    modifier: Modifier = Modifier,
) {
    val model by component.model.subscribeAsState()
    val openDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            if (uri != null) {
                component.onEpubSelected(uri.toString())
            }
        },
    )

    fun openEpubPicker() {
        openDocument.launch(
            arrayOf(
                "application/epub+zip",
                "application/octet-stream",
            ),
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Foreign Words Reader") },
            )
        },
        bottomBar = {
            Surface(elevation = 8.dp) {
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    onClick = ::openEpubPicker,
                ) {
                    Text("Open new book")
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (model.errorMessage != null) {
                Text(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    text = model.errorMessage.orEmpty(),
                    color = MaterialTheme.colors.error,
                )
            }

            when {
                model.isLoading && model.books.isEmpty() -> LoadingReaderState()
                model.books.isEmpty() -> EmptyLibraryState()
                else -> BookList(
                    books = model.books,
                    onBookClicked = component::onBookClicked,
                )
            }
        }
    }
}

@Composable
private fun BookList(
    books: List<BookItem>,
    onBookClicked: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(
            items = books,
            key = BookItem::id,
        ) { book ->
            Column {
                BookRow(
                    book = book,
                    onClick = { onBookClicked(book.uriString) },
                )
                Divider(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f),
                )
            }
        }
    }
}

@Composable
private fun BookRow(
    book: BookItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 124.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(
            coverUriString = book.coverUriString,
            title = book.title,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.subtitle1,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = book.author,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BookProcessingLabel(book = book)
        }
    }
}

@Composable
private fun BookProcessingLabel(book: BookItem) {
    when (book.processingState) {
        BookProcessingState.NotStarted -> Unit
        BookProcessingState.Processing -> {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .width(16.dp)
                        .height(16.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "Processing words...",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.primary,
                )
            }
        }

        BookProcessingState.Completed -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${book.processingTokenCount} tokens processed",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.56f),
            )
        }

        BookProcessingState.Failed -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = book.processingErrorMessage ?: "Word processing failed",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyLibraryState(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text("No books yet.")
    }
}

@Composable
private fun BookCover(
    coverUriString: String?,
    title: String,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(coverUriString) {
        coverUriString?.let { BitmapFactory.decodeFile(it) }
    }
    val coverModifier = modifier
        .width(72.dp)
        .height(104.dp)
        .clip(RoundedCornerShape(6.dp))

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "$title cover",
            modifier = coverModifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = coverModifier.background(Color(0xFFE0E7EF)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title.initials(),
                style = MaterialTheme.typography.h6,
                color = Color(0xFF263238),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun String.initials(): String =
    trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .take(2)
        .joinToString(separator = "") { word -> word.first().uppercaseChar().toString() }
        .ifBlank { "B" }
