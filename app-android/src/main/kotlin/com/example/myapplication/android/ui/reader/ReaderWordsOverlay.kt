package com.example.myapplication.android.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.myapplication.shared.reader.ReaderWordItem
import com.example.myapplication.shared.reader.WordsComponent

@Composable
fun ReaderWordsOverlay(
    component: WordsComponent,
    model: WordsComponent.Model,
    onDismissRequested: () -> Unit = component::onDismissRequested,
    modifier: Modifier = Modifier,
) {
    if (!model.isVisible) {
        return
    }

    Dialog(
        onDismissRequest = onDismissRequested,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .then(modifier),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .align(Alignment.TopCenter),
                elevation = 12.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Words in book",
                            style = MaterialTheme.typography.h6,
                        )
                        IconButton(onClick = onDismissRequested) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close words",
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = wordsSummary(model),
                        style = MaterialTheme.typography.body2,
                        color = if (model.status == WordsComponent.Status.Error) {
                            MaterialTheme.colors.error
                        } else {
                            MaterialTheme.colors.onSurface.copy(alpha = 0.72f)
                        },
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    when (model.status) {
                        WordsComponent.Status.Idle,
                        WordsComponent.Status.Loading -> WordsLoadingState()
                        WordsComponent.Status.Empty -> WordsEmptyState("No important words saved for this book yet.")
                        WordsComponent.Status.Error -> WordsEmptyState(model.errorMessage ?: "Could not load words.")
                        WordsComponent.Status.Loaded -> ReaderWordsList(model.items)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderWordsList(items: List<ReaderWordItem>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = items,
            key = { it.lemma },
        ) { item ->
            WordRow(item)
        }
    }
}

@Composable
private fun WordRow(item: ReaderWordItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        elevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.displayWord,
                    style = MaterialTheme.typography.subtitle1,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.lemma,
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val extraSurfaceWords = item.surfaceWords.drop(1)
                if (extraSurfaceWords.isNotEmpty()) {
                    Text(
                        text = extraSurfaceWords.joinToString(separator = ", "),
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.72f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = item.totalCount.toString(),
                style = MaterialTheme.typography.subtitle2,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.84f),
            )
        }
    }
}

@Composable
private fun WordsEmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.body1,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun WordsLoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

private fun wordsSummary(model: WordsComponent.Model): String =
    when (model.status) {
        WordsComponent.Status.Idle -> "Loading words..."
        WordsComponent.Status.Loading -> "Loading words..."
        WordsComponent.Status.Loaded -> "${model.items.size} words loaded"
        WordsComponent.Status.Empty -> "No words available."
        WordsComponent.Status.Error -> model.errorMessage ?: "Could not load words."
    }
