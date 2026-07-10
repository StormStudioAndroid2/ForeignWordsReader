# Reader and search domain

This document describes the reader and search domain: what the shared reader
contract owns, how search is modeled as a reader-scoped subcomponent, where the
platform Readium boundary lives, and how to add new reader features without
leaking native reader runtime details into shared code or root navigation.

## Responsibility

The reader domain is responsible for the active book reading experience.

It owns:

- the shared `ReaderComponent` contract;
- the current reader model exposed to UI;
- reader-level actions such as back, previous, next, and locator updates;
- reader-scoped subcomponents such as search;
- reader-scoped feature contracts that depend on the active publication,
  navigator, locator, selected text, or reader progress;
- keeping shared reader state independent from Android Readium, iOS Readium,
  Compose, and SwiftUI implementation details.

The current search feature is the first concrete reader-scoped subcomponent. It
owns search visibility, query state, result pages, loading state, errors, and
the selected search locator. Platform code owns the actual Readium search,
navigation, and highlight behavior.

The main source files are:

- `shared/src/commonMain/kotlin/com/example/myapplication/shared/reader/ReaderComponent.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/reader/SearchComponent.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/reader/DefaultSearchComponent.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/reader/ReaderSearchGateway.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/reader/ReaderSearchModels.kt`
- `app-android/src/main/kotlin/com/example/myapplication/android/reader/DefaultAndroidReaderComponent.kt`
- `app-ios-swift/app-ios-swift/reader-platform/DefaultIosReaderComponent.swift`
- `app-ios-swift/app-ios-swift/reader-platform/IosReaderRuntime.swift`
- `app-ios-swift/app-ios-swift/reader-platform/IosReaderSearchGateway.swift`
- `app-ios-swift/app-ios-swift/reader-platform/ReaderView.swift`

## Out of scope

The reader domain does not own:

- app-level root navigation;
- library import or EPUB picker flows;
- Android or iOS Readium rendering internals;
- Android Compose reader UI internals;
- SwiftUI reader UI internals;
- SQLDelight schema ownership;
- NLP preprocessing and frequency index semantics;
- global frequency database generation.

Those responsibilities belong to the root architecture, library, platform,
storage, preprocessing, or tooling domains.

## Reader component contract

`ReaderComponent` is the shared contract for an active reader screen.

It exposes:

- `model`: the current reader state;
- `search`: the reader-scoped search subcomponent;
- `onBackClicked()`: asks the root-provided callback to close the reader;
- `onPreviousClicked()`: asks the platform reader runtime to move backward;
- `onNextClicked()`: asks the platform reader runtime to move forward;
- `onLocatorChanged(locatorJson, readingProgress, currentPage)`: accepts
  platform-originated locator and progress updates.

`ReaderComponent.Model` contains:

- `uriString`: the EPUB URI currently opened by the reader;
- `status`: `Loading`, `Ready`, or `Error`;
- `errorMessage`: the user-visible reader error, if any;
- `readingProgress`: the current book progress as reported by platform code;
- `currentPage`: the current page index or platform page estimate;
- `title`: the current book title shown by reader UI.

The shared reader contract describes what the app needs from a reader. Platform
implementations decide how Readium opens publications, how navigation works, how
progress is calculated, and how locator updates are produced.

## Search component contract

`SearchComponent` is a reader-scoped subcomponent. It should only be used while
there is an active reader publication.

`SearchComponent.Model` contains:

- `isVisible`: whether the search overlay is visible;
- `query`: the current search query text;
- `status`: `Idle`, `Loading`, `Results`, `Empty`, or `Error`;
- `results`: the current accumulated result list;
- `selectedLocatorJson`: the locator selected by the user, if any;
- `errorMessage`: the current user-visible search error, if any;
- `isLoadingMore`: whether another result page is loading;
- `canLoadMore`: whether the current result set may have another page.

The search flow is:

1. `onOpenRequested()` shows the search overlay.
2. `onQueryChanged(query)` updates the query and clears the current error.
3. A blank query resets search results while keeping the overlay visibility.
4. `onSearchSubmitted()` trims the query, cancels the previous search, enters
   `Loading`, clears old results, and asks the gateway to start a new search.
5. The first gateway page moves the model to `Results` and stores returned
   result items.
6. Gateway completion moves the model to `Empty` when there are no results or
   keeps `Results` when at least one result exists.
7. Gateway error moves the model to `Error` and stores `errorMessage`.
8. `onLoadNextPage()` appends another page only when the model is in
   `Results`, not already loading, and `canLoadMore` is true.
9. `onClearQueryClicked()` clears the query and resets search state.
10. `onResultClicked(locatorJson)` stores the selected locator and asks the
    gateway to navigate to it.
11. Successful navigation hides the overlay. Failed navigation keeps the overlay
    visible and stores the error.

`DefaultSearchComponent` uses an internal request id to ignore stale callbacks
from an older search request. A platform gateway may call callbacks
asynchronously, and shared search state must remain valid when those callbacks
arrive later than the user action that replaced them.

## Gateway contract

`ReaderSearchGateway` is the narrow capability boundary between shared search
state and platform Readium runtime.

It exposes:

- `startSearch(query, onPage, onComplete, onError)`;
- `loadMore(onPage, onComplete, onError)`;
- `cancelSearch()`;
- `navigateToResult(locatorJson, onSuccess, onError)`.

The gateway owns platform work that shared code cannot perform directly:

- accessing the current Readium `Publication`;
- owning or advancing the Readium search iterator;
- using the active navigator to move to a locator;
- applying or clearing search highlight decorations;
- handling native threading, lifecycle, and cancellation rules.

Shared code should pass only domain models, strings, ids, locator JSON, and
progress values across this boundary. It must not expose Readium Kotlin,
Readium Swift, Compose, SwiftUI, Android, or UIKit types in `commonMain`.

Use feature-specific gateways rather than one large generic reader gateway. For
example, search uses `ReaderSearchGateway`; a future table of contents feature
should use a dedicated contents gateway if it needs publication data; a future
dictionary popup should use a dedicated selection or lookup gateway if it needs
native selected text.

`EmptyReaderSearchGateway` is a fallback for platforms where search is not
available. It reports unavailable search errors and should not be treated as a
production Readium implementation.

## Platform responsibilities

Android and iOS implement the shared reader/search contracts with platform
reader runtimes.

Android owns:

- opening the EPUB with Readium Kotlin;
- hosting the Android navigator;
- restoring and saving the last readable EPUB URI and locator;
- reporting locator and progress changes to `ReaderComponent`;
- implementing `ReaderSearchGateway` through Readium publication search;
- mapping Readium locators to `ReaderSearchResultItem`;
- navigating to a selected search locator;
- applying search highlight decorations;
- rendering reader chrome and search overlay in Compose.

iOS Swift owns:

- resolving security-scoped EPUB access;
- opening the EPUB with Readium Swift Toolkit;
- hosting the Swift reader view controller and navigator;
- restoring and saving the last readable EPUB URI and locator;
- reporting locator and progress changes to `ReaderComponent`;
- implementing `ReaderSearchGateway` through Readium Swift publication search;
- mapping Readium locators to `ReaderSearchResultItem`;
- navigating to a selected search locator;
- applying search highlight decorations;
- rendering reader chrome and search overlay in SwiftUI.

The platform runtime decides how to translate native Readium events into shared
reader model updates. The shared reader contract decides which state and actions
the rest of the app can depend on.

## Current platform note

iOS currently routes `onPreviousClicked()` and `onNextClicked()` to reader
runtime navigation. Android currently keeps those actions as no-ops in the
reader component.

Any new feature that depends on page-level previous/next behavior must either:

- implement platform parity in the same change; or
- document the temporary platform gap and keep the feature from depending on
  unavailable behavior.

This note is about current implementation state, not the desired long-term
contract.

## Adding reader features

Use this flow when adding a new feature inside the reader.

### 1. Classify the feature

Choose the smallest owner that matches the user experience.

- App-level full screen: add a root graph screen only when the feature can
  exist without an active reader publication.
- Active-book overlay: add a reader subcomponent when the feature depends on
  the active publication, locator, search session, reader progress, or selected
  text.
- Temporary reader control: keep it in reader UI when it has no meaningful
  shared state, or promote it to a reader subcomponent when it has loading,
  errors, results, or business logic.
- Readium or native capability: hide it behind a platform gateway or runtime
  method and expose only domain data to shared code.

Reader-scoped features should not be added to `RootComponent` just because they
are visually full-screen. If dismissing the surface returns to the same active
book state, it usually belongs inside the reader domain.

### 2. Define shared state

Add a shared subcomponent when the feature has any of these:

- visibility state;
- async loading;
- errors;
- result lists;
- selected item state;
- pagination;
- cancellation;
- user actions that should be unit-tested in `commonTest`.

Keep the feature in platform UI when it is only a native button or purely visual
reader control with no shared state contract.

Suggested future subcomponents:

- `ContentsComponent` for table of contents;
- `ReaderSettingsComponent` for reader preferences;
- `BookmarksComponent` for book-scoped bookmarks;
- `DictionaryComponent` for selected-word lookup;
- `VocabularyPanelComponent` for book-scoped vocabulary surfaces.

### 3. Define the platform boundary

Add a feature-specific gateway when the feature needs any of these platform or
Readium objects:

- `Publication`;
- navigator state or navigation commands;
- `Locator` parsing or generation;
- native text selection;
- decorations or highlights;
- platform text APIs;
- platform-specific reader preferences.

The shared gateway contract should accept and return only stable domain data:

- strings;
- ids;
- locator JSON;
- progression values;
- simple data classes from `commonMain`;
- success, completion, and error callbacks.

Do not pass Readium, Android, iOS, Compose, SwiftUI, UIKit, or lifecycle objects
through shared contracts.

### 4. Wire through `ReaderComponent`

Reader-scoped features should hang from `ReaderComponent` or a reader
subcomponent.

To add one:

1. Add a property to `ReaderComponent`, such as `contents`, `settings`,
   `bookmarks`, or `dictionary`.
2. Add the shared component interface and default implementation when there is
   shared behavior.
3. Add a feature-specific gateway only when platform runtime access is needed.
4. Create the subcomponent in Android and iOS reader component factories next
   to the platform reader runtime.
5. Keep the root navigation graph unchanged while the feature requires an
   active reader.

If the feature later becomes app-wide, document that migration before moving it
to root navigation.

### 5. Render on both platforms

Every reader feature needs an explicit platform rendering decision.

Android should add the Compose reader chrome, overlay, dialog, or panel branch
inside the reader UI.

iOS should add the SwiftUI reader chrome, overlay, sheet, or panel branch inside
the reader UI.

If one platform cannot support the feature yet, document the parity gap in this
domain document and keep shared behavior safe when that gateway reports
unavailable capability.

### 6. Decide persistence ownership

Pick the persistence owner before implementing the feature.

- Locator and progress belong to reader runtime or reader persistence.
- Book metadata and book indexes belong to the library, store, or preprocessing
  domains.
- Reader UI preferences belong to a reader settings persistence contract.
- App-wide vocabulary, dictionary history, or learning state should get its own
  domain when it can exist outside the active reader.
- Temporary overlay state should stay in the reader subcomponent model.

Do not add storage to the reader domain just because a feature is launched from
the reader. Store data in the domain that owns the durable concept.

### 7. Add tests with the feature

Every new reader feature should add tests for:

- initial shared component state;
- open and dismiss behavior;
- primary action callback;
- gateway success;
- gateway error;
- cancellation or stale callback behavior when relevant;
- reader progress and active reader state preservation;
- Android rendering branch;
- iOS rendering branch.

Platform integration with real Readium can remain smoke or integration coverage
when the behavior cannot be tested without a native reader runtime.

## Verification

Use `docs/agent-harness.md` for the full verification matrix.

For shared reader/search component changes, the default command is:

```bash
./gradlew :shared:test
```

`DefaultSearchComponentTest` is the expected coverage home for search state,
pagination, stale callbacks, result navigation, and errors. Future reader
subcomponents should add their own focused common tests when they introduce
shared state or gateway contracts.

Run Android and iOS runbooks when a change touches reader runtime integration,
Readium navigation/search/highlighting, overlay rendering branches, or platform
locator persistence. Platform smoke is enough for behavior that requires real
Readium runtime.

## Feature examples

Use these examples as starting points for future reader work.

Table of contents:

- belongs in the reader domain because it depends on the active publication;
- should use a `ContentsComponent`;
- should use a platform gateway if publication TOC extraction is not available
  in shared code;
- should navigate by locator JSON or href/progression data, not by Readium
  native objects.

Reader settings:

- belongs in the reader domain when settings affect the active navigator;
- should use shared state for selected theme, font size, margins, and similar
  preferences when those are cross-platform product concepts;
- should use platform runtime methods to apply settings to the native reader.

Bookmarks:

- belong in the reader domain only for active-book capture and display;
- durable bookmark storage needs an explicit persistence owner;
- bookmark records should store stable book id or URI plus locator JSON and
  user-facing title/context.

Dictionary popup:

- belongs in the reader domain when launched from selected reader text;
- should use a platform selection gateway if selected text is native-only;
- should use a separate lookup gateway or domain if dictionary data becomes
  app-wide.

Vocabulary panel:

- belongs in the reader domain when it is tied to the active book, current
  locator, or reading session;
- should become an app-level screen if it represents an app-wide vocabulary
  list that can be opened without an active reader.

## Test contract

The current shared search component should be covered by
`DefaultSearchComponentTest` cases for:

- opening and dismissing the search overlay;
- updating the query;
- resetting search state for a blank query;
- submitting a query and entering `Loading`;
- receiving the first result page and entering `Results`;
- completing with no results and entering `Empty`;
- receiving an error and entering `Error`;
- ignoring stale callbacks from an older search request;
- clearing the query and resetting state.

Pagination should be covered by tests for:

- appending results from `onLoadNextPage()`;
- disabling further loading after completion;
- preserving existing results when a load-more error occurs;
- entering `Error` when load-more fails before any result exists;
- ignoring load-more when status is not `Results`;
- ignoring load-more when already loading;
- ignoring load-more when `canLoadMore` is false.

Result navigation should be covered by tests for:

- setting `selectedLocatorJson` when a result is clicked;
- hiding the overlay after successful navigation;
- keeping the overlay visible after failed navigation;
- exposing navigation failure through `errorMessage`.

Gateway behavior should be covered where it can be tested without a real native
reader runtime:

- unavailable gateway errors;
- invalid locator JSON;
- locator-to-result mapping;
- cancellation behavior;
- iterator completion behavior;
- highlight target selection.

Real EPUB search, native navigation, and platform highlight behavior can remain
platform smoke or integration tests when they require Readium runtime.

Every new reader feature must document and later test:

- visibility, open, and dismiss behavior;
- primary user action callback;
- gateway success and error behavior;
- cancellation or stale callback behavior when relevant;
- reader progress preservation while the feature opens and closes;
- Android rendering branch;
- iOS rendering branch.

## Regression rules

Do not add a reader feature without deciding:

- whether it is root-scoped or reader-scoped;
- whether it needs shared component state;
- whether it needs a feature-specific gateway;
- which domain owns durable persistence;
- which Android and iOS UI branches render it;
- which tests protect its shared behavior.

Every new field shown in a reader subcomponent model must be backed by at least
one shared component test. Every new platform capability exposed to shared code
must have a documented gateway contract.
