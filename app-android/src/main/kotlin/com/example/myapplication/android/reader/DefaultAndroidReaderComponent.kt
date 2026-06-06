package com.example.myapplication.android.reader

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import androidx.fragment.app.FragmentFactory
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import androidx.core.content.edit
import androidx.core.net.toUri
import com.example.myapplication.shared.reader.DefaultSearchComponent
import com.example.myapplication.shared.reader.ReaderComponent
import com.example.myapplication.shared.reader.ReaderSearchGateway
import com.example.myapplication.shared.reader.ReaderSearchPage
import com.example.myapplication.shared.reader.ReaderSearchResultItem
import com.example.myapplication.shared.reader.SearchComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubDefaults
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.SearchIterator
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toAbsoluteUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

private const val PreferencesName = "readium_reader"
private const val LastEpubUriKey = "last_epub_uri"
private const val SearchDecorationGroup = "search"
private const val SearchDecorationId = "selected-search-result"
private val SearchHighlightTint = Color.argb(110, 255, 214, 10)

internal class DefaultAndroidReaderComponent(
    componentContext: ComponentContext,
    application: Application,
    uriString: String,
    private val onFinished: () -> Unit,
) : ReaderComponent, ComponentContext by componentContext {

    private val runtime = instanceKeeper.getOrCreate(key = "reader:$uriString") {
        ReaderRuntime(
            application = application,
            uriString = uriString,
        )
    }

    override val model: Value<ReaderComponent.Model> = runtime.model
    override val search: SearchComponent = DefaultSearchComponent(componentContext, runtime.searchGateway)

    val androidModel: Value<AndroidReaderModel> = runtime.androidModel

    override fun onBackClicked() {
        onFinished()
    }

    override fun onPreviousClicked() = Unit

    override fun onNextClicked() = Unit

    override fun onLocatorChanged(
        locatorJson: String,
        readingProgress: Double,
        currentPage: Int,
    ) {
        runtime.saveLocator(
            locatorJson = locatorJson,
            readingProgress = readingProgress,
            currentPage = currentPage,
        )
    }

    fun attachNavigator(navigator: EpubNavigatorFragment) {
        runtime.attachNavigator(navigator)
    }

    fun detachNavigator(navigator: EpubNavigatorFragment) {
        runtime.detachNavigator(navigator)
    }

    @OptIn(ExperimentalReadiumApi::class)
    private class ReaderRuntime(
        private val application: Application,
        private val uriString: String,
    ) : InstanceKeeper.Instance {

        private val preferences = application.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val httpClient = DefaultHttpClient()
        private val assetRetriever = AssetRetriever(application.contentResolver, httpClient)
        private val publicationParser = DefaultPublicationParser(
            context = application,
            httpClient = httpClient,
            assetRetriever = assetRetriever,
            pdfFactory = null,
        )
        private val publicationOpener = PublicationOpener(
            publicationParser = publicationParser,
            contentProtections = emptyList(),
        )

        val model = MutableValue(
            ReaderComponent.Model(
                uriString = uriString,
                status = ReaderComponent.Status.Loading,
                title = "Loading"
            ),
        )
        val androidModel = MutableValue<AndroidReaderModel>(
            AndroidReaderModel.Loading(uriString = uriString),
        )

        private var publication: Publication? = null
        private var searchIterator: SearchIterator? = null
        private var searchJob: Job? = null
        private var navigator: EpubNavigatorFragment? = null
        private var selectedSearchLocator: Locator? = null
        private var readerVersion = 0
        val searchGateway: ReaderSearchGateway = AndroidReaderSearchGateway()

        init {
            open()
        }

        fun saveLocator(
            locatorJson: String,
            readingProgress: Double,
            currentPage: Int,
        ) {
            preferences.edit {
                putString(locatorKey(uriString), locatorJson)
            }
            model.value = model.value.copy(
                readingProgress = readingProgress.coerceIn(0.0, 1.0),
                currentPage = currentPage,
            )
        }

        private fun open() {
            scope.launch {
                model.value = ReaderComponent.Model(
                    uriString = uriString,
                    status = ReaderComponent.Status.Loading,
                    title = "Loading"
                )
                androidModel.value = AndroidReaderModel.Loading(uriString = uriString)

                runCatching {
                    withContext(Dispatchers.IO) {
                        openPublication()
                    }
                }.onSuccess { ready ->
                    cancelSearchSession()
                    publication?.close()
                    publication = ready.publication
                    model.value = ReaderComponent.Model(
                        uriString = uriString,
                        status = ReaderComponent.Status.Ready,
                        readingProgress = ready.readingProgress,
                        currentPage = ready.currentPage,
                        title = ready.publication.metadata.title.normalizedOr("Untitled book")
                    )
                    androidModel.value = AndroidReaderModel.Ready(
                        uriString = uriString,
                        readerKey = ready.readerKey,
                        fragmentFactory = ready.fragmentFactory,
                        publication = ready.publication,
                    )
                    preferences.edit {
                        putString(LastEpubUriKey, uriString)
                    }
                }.onFailure { error ->
                    val message = error.message ?: "Could not open this EPUB."
                    model.value = ReaderComponent.Model(
                        uriString = uriString,
                        status = ReaderComponent.Status.Error,
                        errorMessage = message,
                        title = "Error"
                    )
                    androidModel.value = AndroidReaderModel.Error(
                        uriString = uriString,
                        message = message,
                    )
                }
            }
        }

        private suspend fun openPublication(): ReadyPublication {
            val uri = uriString.toUri()
            takePersistableReadPermission(uri)

            val absoluteUrl = uri.toAbsoluteUrl()
                ?: error("Could not resolve this EPUB URI.")
            val asset = assetRetriever.retrieve(absoluteUrl).getOrElse { error ->
                throw IllegalStateException("Could not read this EPUB: $error")
            }
            val openedPublication = publicationOpener.open(
                asset = asset,
                allowUserInteraction = true,
            ).getOrElse { error ->
                throw IllegalStateException("Could not parse this EPUB: $error")
            }

            val restoredLocator = restoreLocator(uriString)
            val navigatorFactory = EpubNavigatorFactory(
                publication = openedPublication,
                configuration = EpubNavigatorFactory.Configuration(
                    defaults = EpubDefaults(scroll = false),
                ),
            )
            val fragmentFactory = navigatorFactory.createFragmentFactory(
                initialLocator = restoredLocator,
                initialPreferences = EpubPreferences(scroll = false),
                configuration = EpubNavigatorFragment.Configuration(
                    // The app root already applies system bar insets, so letting Readium add its
                    // own cutout padding causes the paginated viewport to grow after inset updates.
                    shouldApplyInsetsPadding = false,
                ),
            )

            readerVersion += 1
            return ReadyPublication(
                publication = openedPublication,
                readerKey = "$uriString#$readerVersion",
                fragmentFactory = fragmentFactory,
                readingProgress = restoredLocator?.readingProgress ?: 0.0,
                currentPage = restoredLocator?.locations?.position ?: 0,
            )
        }

        private fun takePersistableReadPermission(uri: Uri) {
            try {
                application.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // Some providers do not offer persistable permissions. The file can still be opened now.
            }
        }

        private fun restoreLocator(uriString: String): Locator? {
            val json = preferences.getString(locatorKey(uriString), null)
                ?: return null

            return runCatching {
                Locator.fromJSON(JSONObject(json))
            }.getOrNull()
        }

        fun attachNavigator(navigator: EpubNavigatorFragment) {
            this.navigator = navigator
            renderSelectedSearchLocator()
        }

        fun detachNavigator(navigator: EpubNavigatorFragment) {
            if (this.navigator == navigator) {
                this.navigator = null
            }
        }

        private fun cancelSearchSession() {
            searchJob?.cancel()
            searchJob = null
            searchIterator?.close()
            searchIterator = null
            selectedSearchLocator = null
            renderSearchHighlight(locator = null)
        }

        private fun loadNextPage(
            onPage: (ReaderSearchPage) -> Unit,
            onComplete: () -> Unit,
            onError: (String) -> Unit,
        ) {
            val iterator = searchIterator
            if (iterator == null) {
                onComplete()
                return
            }

            searchJob?.cancel()
            searchJob = scope.launch {
                val collection = iterator.next().getOrElse { error ->
                    onError(error.message ?: "Search failed.")
                    return@launch
                }

                if (collection == null) {
                    onComplete()
                    return@launch
                }

                onPage(
                    ReaderSearchPage(
                        results = collection.locators.map(::toSearchResultItem),
                    ),
                )
            }
        }

        private fun toSearchResultItem(locator: Locator): ReaderSearchResultItem {
            val locatorJson = locator.toJSON().toString()
            return ReaderSearchResultItem(
                id = locatorJson,
                locatorJson = locatorJson,
                title = locator.title.normalizedOr(model.value.title.normalizedOr("Search result")),
                before = locator.text.before.orEmpty(),
                highlight = locator.text.highlight.orEmpty(),
                after = locator.text.after.orEmpty(),
                progression = locator.readingProgress,
                position = locator.locations.position ?: 0,
            )
        }

        private fun renderSelectedSearchLocator() {
            renderSearchHighlight(selectedSearchLocator)
        }

        private fun renderSearchHighlight(locator: Locator?) {
            val currentNavigator = navigator as? DecorableNavigator ?: return
            scope.launch {
                if (locator == null) {
                    currentNavigator.applyDecorations(
                        decorations = emptyList(),
                        group = SearchDecorationGroup,
                    )
                    return@launch
                }

                if (!currentNavigator.supportsDecorationStyle(Decoration.Style.Highlight::class)) {
                    return@launch
                }

                currentNavigator.applyDecorations(
                    decorations = listOf(
                        Decoration(
                            id = SearchDecorationId,
                            locator = locator,
                            style = Decoration.Style.Highlight(
                                tint = SearchHighlightTint,
                                isActive = true,
                            ),
                        ),
                    ),
                    group = SearchDecorationGroup,
                )
            }
        }

        override fun onDestroy() {
            cancelSearchSession()
            scope.cancel()
            publication?.close()
            publication = null
        }

        private inner class AndroidReaderSearchGateway : ReaderSearchGateway {
            override fun startSearch(
                query: String,
                onPage: (ReaderSearchPage) -> Unit,
                onComplete: () -> Unit,
                onError: (String) -> Unit,
            ) {
                cancelSearchSession()

                val currentPublication = publication
                if (currentPublication == null) {
                    onError("Reader is not ready.")
                    return
                }

                searchJob = scope.launch {
                    val iterator = currentPublication.search(query = query)
                    if (iterator == null) {
                        onError("This publication is not searchable.")
                        return@launch
                    }

                    searchIterator = iterator
                    loadNextPage(
                        onPage = onPage,
                        onComplete = onComplete,
                        onError = onError,
                    )
                }
            }

            override fun loadMore(
                onPage: (ReaderSearchPage) -> Unit,
                onComplete: () -> Unit,
                onError: (String) -> Unit,
            ) {
                loadNextPage(
                    onPage = onPage,
                    onComplete = onComplete,
                    onError = onError,
                )
            }

            override fun cancelSearch() {
                cancelSearchSession()
            }

            override fun navigateToResult(
                locatorJson: String,
                onSuccess: () -> Unit,
                onError: (String) -> Unit,
            ) {
                val locator = runCatching {
                    Locator.fromJSON(JSONObject(locatorJson))
                }.getOrNull()
                if (locator == null) {
                    onError("Could not open this search result.")
                    return
                }

                val currentNavigator = navigator
                if (currentNavigator == null) {
                    onError("Reader is not ready.")
                    return
                }

                scope.launch {
                    // Search locators include text excerpts, which makes Readium take the
                    // text-based scroll path and can leave the passage vertically offset in the
                    // paginated viewport. Jump with a plain location locator instead.
                    val navigationLocator = locator.copy(text = Locator.Text())
                    val didNavigate = currentNavigator.go(navigationLocator, animated = true)
                    if (!didNavigate) {
                        onError("Could not open this search result.")
                        return@launch
                    }

                    selectedSearchLocator = locator
                    renderSelectedSearchLocator()
                    onSuccess()
                }
            }
        }
    }

    private data class ReadyPublication(
        val publication: Publication,
        val readerKey: String,
        val fragmentFactory: FragmentFactory,
        val readingProgress: Double,
        val currentPage: Int,
    )
}

sealed interface AndroidReaderModel {
    val uriString: String

    data class Loading(
        override val uriString: String,
    ) : AndroidReaderModel

    data class Ready(
        override val uriString: String,
        val readerKey: String,
        val fragmentFactory: FragmentFactory,
        val publication: Publication,
    ) : AndroidReaderModel

    data class Error(
        override val uriString: String,
        val message: String,
    ) : AndroidReaderModel
}

internal fun lastReadableEpubUriString(context: Context): String? {
    val uriString = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        .getString(LastEpubUriKey, null)
        ?: return null
    val uri = uriString.toUri()

    return uriString.takeIf {
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission
        }
    }
}

private fun locatorKey(uriString: String): String = "locator:$uriString"

private fun String?.normalizedOr(fallback: String): String =
    this?.trim()?.takeIf(String::isNotEmpty) ?: fallback

private val Locator.readingProgress: Double
    get() = (locations.totalProgression ?: locations.progression ?: 0.0).coerceIn(0.0, 1.0)
