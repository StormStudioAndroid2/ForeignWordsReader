# Android Reader Selection Context Menu Investigation

## Purpose

This note investigates the Android APIs available for a custom EPUB
selected-text menu with three actions:

- `Dictionary`;
- `Translate`;
- `Copy`.

It records technical feasibility and platform constraints only. It does not
define feature architecture or an implementation plan.

## Current Environment

ForeignWordsReader currently compiles and targets Android SDK 35 and uses
Readium Kotlin Toolkit 3.1.2. The reader is rendered by
`EpubNavigatorFragment`.

Readium 3.1.2 exposes the supported selection-menu extension point
`EpubNavigatorFragment.Configuration.selectionActionModeCallback`. This takes
an Android `ActionMode.Callback` and avoids depending on private WebView
internals. Readium added this hook because activity-level action-mode
customization is not reliable for its WebView selection menu on Android 12 and
later.

## Selection Menu Hook

`ActionMode.Callback` controls the contextual toolbar lifecycle:

- `onCreateActionMode()` creates the initial menu;
- `onPrepareActionMode()` updates it when the action mode is invalidated;
- `onActionItemClicked()` receives action taps;
- `onDestroyActionMode()` receives toolbar teardown.

With Readium 3.1.2, supplying `selectionActionModeCallback` substitutes the
callback used by the underlying reader WebView. The custom callback must
therefore add the required actions itself; default WebView items such as Copy
must not be assumed to remain present automatically.

Readium's `EpubNavigatorFragment` implements `SelectableNavigator`. While the
selection is still active, its text is available from:

```kotlin
selectableNavigator.currentSelection()
    ?.locator
    ?.text
    ?.highlight
    ?.trim()
```

`currentSelection()` is a suspending function. It must be called from a
coroutine after a menu action is tapped, without blocking the main thread. The
result can be `null`, and the highlighted text can become empty if the
selection disappears before the coroutine reads it.

## Copy

Copy can be provided directly with the Android clipboard API:

```kotlin
val clip = ClipData.newPlainText("Selected text", selectedText)
clipboardManager.setPrimaryClip(clip)
```

`ClipboardManager.setText()` is deprecated; `setPrimaryClip()` is the current
API. Empty or whitespace-only text should not be copied. Finishing the action
mode closes the contextual toolbar; clearing the Readium selection separately
is optional product behavior.

## Dictionary Handoff Options

### `ACTION_DEFINE`

`Intent.ACTION_DEFINE` is the most specific public Android action for asking
another activity to define selected text. It was added in API 29 and receives
the word or phrase through `Intent.EXTRA_TEXT`.

Advantages:

- expresses dictionary intent directly;
- allows any compatible installed handler;
- does not require a vendor package or URL format.

Limitations:

- it requires an API 29 guard;
- many dictionary applications do not register a handler;
- handler availability must be checked before launch.

### `ACTION_PROCESS_TEXT`

`Intent.ACTION_PROCESS_TEXT` is the broader compatibility route available
since API 23. A read-only lookup intent uses:

```kotlin
Intent(Intent.ACTION_PROCESS_TEXT).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_PROCESS_TEXT, selectedText)
    putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
}
```

This action can expose dictionary, translation, writing, and other text tools.
It is broader than dictionary lookup, so the resulting chooser is not
guaranteed to contain only dictionaries.

### Generic alternatives

- `ACTION_WEB_SEARCH` with `SearchManager.QUERY` can search for the text, but
  does not guarantee a definition result.
- `ACTION_VIEW` can open an HTTPS dictionary URL or vendor deep link, but then
  the app owns URL encoding, provider choice, browser behavior, and vendor
  compatibility.

These alternatives are technically possible but are not universal dictionary
handoffs.

## Translation Handoff Options

### `ACTION_TRANSLATE`

`Intent.ACTION_TRANSLATE` is the most specific public Android translation
action. It was added in API 29 and receives source text through
`Intent.EXTRA_TEXT`.

Advantages:

- expresses translation intent directly;
- remains independent of a specific translator package;
- can be presented through the normal Android chooser.

Limitations:

- it requires an API 29 guard;
- installed translators do not consistently register this action;
- Android does not guarantee a handler or a translation result payload.

### `ACTION_PROCESS_TEXT`

The same read-only `ACTION_PROCESS_TEXT` form included in the Dictionary picker
can expose translators that integrate with Android text processing. For
Translate it remains the fallback when no `ACTION_TRANSLATE` handler is
available, with better compatibility but a less focused chooser.

### Generic alternatives

- `ACTION_SEND` with MIME type `text/plain` and `Intent.EXTRA_TEXT` opens the
  general share sheet. A translator may appear, but so will unrelated sharing
  targets.
- `ACTION_VIEW` can open a translator website or vendor deep link. This is not
  portable and requires provider-specific URL handling.

Neither alternative provides a universal translation API.

## Package Visibility

On Android 11 and later, package visibility can hide implicit-intent handlers
from `PackageManager` queries. Capability checks for this feature require
matching manifest `<queries>` declarations for:

- `android.intent.action.DEFINE`;
- `android.intent.action.TRANSLATE`;
- `android.intent.action.PROCESS_TEXT` with MIME type `text/plain`.

The application manifest already declares these three queries. Launching an
implicit intent does not by itself require visibility, but preflight calls such
as `resolveActivity()` or `queryIntentActivities()` must be covered by matching
queries.

## `ACTION_LOOK_UP_TEXT`

`Intent.ACTION_LOOK_UP_TEXT` is not present in the Android SDK 35 or SDK 36
platform JARs installed for this project. It cannot be used with the current
compile SDK.

`android.R.id.lookUpText` is a framework menu-item resource ID, not an intent
action. It identifies a platform menu entry where that resource is available;
passing it to `Intent` would not open a dictionary.

## Technical Constraints

- Selected text may contain a word, phrase, punctuation, or line breaks; the
  reader does not guarantee a single normalized word.
- A selection can disappear before asynchronous text retrieval completes.
- Blank text must be ignored instead of launching an empty chooser.
- Intent handlers vary by Android version, device image, and installed apps.
- Dictionary should combine compatible `ACTION_DEFINE` and
  `ACTION_PROCESS_TEXT` handlers in an explicit-choice picker until the user
  checks `Always use this app`; a remembered package is rediscovered and
  launched directly on later requests. Translate should use `ACTION_TRANSLATE`
  with `ACTION_PROCESS_TEXT` as the broader fallback.
- The remembered Dictionary choice is one global Android-private package name,
  has no time expiry, and must be cleared if the package is no longer among the
  current handlers or its explicit launch fails. A still-valid choice has no
  in-app reset control and otherwise remains until application data is cleared.
- A chooser launch is one-way. Dictionary and translator activities are not
  expected to return definitions or translated text to the reader.
- Launch the chooser from the attached reader `Activity`, never from an
  application context with `FLAG_ACTIVITY_NEW_TASK`, so compatible handlers
  remain associated with the reader task.
- The selection action mode must consume the first system Back event and close
  itself before Decompose is allowed to pop the reader.
- If Dictionary discovery is empty, or neither the primary nor fallback
  Translate action has a handler, the app must keep the reader open and report
  the unavailable action without crashing.
- The menu can remain Android-native in appearance while using app-supplied
  actions through `ActionMode.Callback`.

## Conclusion

The requested menu is technically supported by the pinned Readium version.
The public hook is `selectionActionModeCallback`, selected text is available
through `SelectableNavigator`, and Copy can be implemented with the Android
clipboard API.

For Dictionary, combine `ACTION_DEFINE` and read-only `ACTION_PROCESS_TEXT`
handlers because installed dictionary apps do not consistently expose the
specific Define action. Store only an explicitly approved package name and
rebuild its preferred Define-or-Process intent on each use rather than
persisting a component or action. For Translate, use `ACTION_TRANSLATE` first
and read-only `ACTION_PROCESS_TEXT` as fallback. Web search, share, URLs, and
deep links are possible but provide weaker semantics or introduce provider
coupling.

## References

- Readium Kotlin 3.1.2 `EpubNavigatorFragment.Configuration`:
  <https://readium.org/kotlin-toolkit/3.1.2/api/readium/readium-navigator/org.readium.r2.navigator.epub/-epub-navigator-fragment/-configuration/index.html>
- Readium Kotlin Toolkit changelog:
  <https://github.com/readium/kotlin-toolkit/blob/develop/CHANGELOG.md>
- Android `ActionMode.Callback`:
  <https://developer.android.com/reference/android/view/ActionMode.Callback>
- Android `Intent` actions and extras:
  <https://developer.android.com/reference/android/content/Intent>
- Android package visibility declarations:
  <https://developer.android.com/training/package-visibility/declaring>
- Android `ClipboardManager.setPrimaryClip()`:
  <https://developer.android.com/reference/android/content/ClipboardManager#setPrimaryClip(android.content.ClipData)>
- Android `ClipData.newPlainText()`:
  <https://developer.android.com/reference/android/content/ClipData#newPlainText(java.lang.CharSequence,java.lang.CharSequence)>
