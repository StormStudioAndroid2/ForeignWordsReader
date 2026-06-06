package com.example.myapplication.android.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.myapplication.shared.reader.ReaderSearchResultItem
import com.example.myapplication.shared.reader.SearchComponent

@Composable
fun ReaderSearchOverlay(
    component: SearchComponent,
    model: SearchComponent.Model,
    onDismissRequested: () -> Unit = component::onDismissRequested,
    onResultClicked: (String) -> Unit = component::onResultClicked,
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
                            text = "Search in book",
                            style = MaterialTheme.typography.h6,
                        )
                        IconButton(onClick = onDismissRequested) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close search",
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.weight(1f),
                            value = model.query,
                            onValueChange = component::onQueryChanged,
                            label = { Text("Word or phrase") },
                            singleLine = true,
                        )
                        Button(onClick = component::onSearchSubmitted) {
                            Text("Search")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val summary = when (model.status) {
                            SearchComponent.Status.Idle -> "Enter a word or phrase."
                            SearchComponent.Status.Loading -> "Searching..."
                            SearchComponent.Status.Results -> "${model.results.size} results loaded"
                            SearchComponent.Status.Empty -> "No matches found."
                            SearchComponent.Status.Error -> model.errorMessage ?: "Search failed."
                        }
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.body2,
                            color = if (model.status == SearchComponent.Status.Error) {
                                MaterialTheme.colors.error
                            } else {
                                MaterialTheme.colors.onSurface.copy(alpha = 0.72f)
                            }
                        )
                        Row {
                            if (model.query.isNotEmpty() || model.results.isNotEmpty()) {
                                TextButton(onClick = component::onClearQueryClicked) {
                                    Text("Clear")
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    when (model.status) {
                        SearchComponent.Status.Idle -> SearchEmptyState("Search opens matching passages with context.")
                        SearchComponent.Status.Loading -> SearchLoadingState()
                        SearchComponent.Status.Empty -> SearchEmptyState("No matches found for \"${model.query}\".")
                        SearchComponent.Status.Error -> SearchEmptyState(model.errorMessage ?: "Search failed.")
                        SearchComponent.Status.Results -> ReaderSearchResults(
                            model = model,
                            onResultClicked = onResultClicked,
                            onLoadNextPage = component::onLoadNextPage,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderSearchResults(
    model: SearchComponent.Model,
    onResultClicked: (String) -> Unit,
    onLoadNextPage: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(
            items = model.results,
            key = { _, item -> item.id },
        ) { index, item ->
            if (index == model.results.lastIndex && model.canLoadMore && !model.isLoadingMore) {
                LaunchedEffect(item.id, model.results.size, model.canLoadMore) {
                    onLoadNextPage()
                }
            }

            SearchResultRow(
                item = item,
                isSelected = item.locatorJson == model.selectedLocatorJson,
                onClick = { onResultClicked(item.locatorJson) },
            )
        }

        if (model.isLoadingMore) {
            item(key = "loading-more") {
                SearchLoadingMoreState()
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    item: ReaderSearchResultItem,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = if (isSelected) 4.dp else 1.dp,
        color = if (isSelected) {
            MaterialTheme.colors.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colors.surface
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.subtitle2,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = buildAnnotatedString {
                    append(item.before)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(item.highlight)
                    }
                    append(item.after)
                },
                style = MaterialTheme.typography.body2,
            )
            Text(
                text = searchProgressLabel(item),
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun SearchEmptyState(message: String) {
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
private fun SearchLoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun SearchLoadingMoreState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

private fun searchProgressLabel(item: ReaderSearchResultItem): String {
    val percent = (item.progression * 100).toInt().coerceIn(0, 100)
    return if (item.position > 0) {
        "Position ${item.position} • ${percent}%"
    } else {
        "${percent}%"
    }
}
