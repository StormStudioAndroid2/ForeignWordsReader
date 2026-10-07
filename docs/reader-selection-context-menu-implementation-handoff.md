# Reader Selection Context Menu Implementation Handoff

## Status

Ready for implementation on Android and iOS.

This document consolidates the completed platform investigations into one
decision-complete development task. The technical background remains in:

- `docs/android-reader-selection-context-menu.md`;
- `docs/ios-reader-selection-context-menu.md`.

For selected reader text, this handoff supersedes older statements in
`docs/external-dictionary-translation-plan.md` that require preserving every
native selection action. The selection UI and handles remain platform-native,
but the required app-owned commands are `Dictionary`, `Translate`, and `Copy`.
The existing Frequent words behavior is unchanged.

## Goal

When the user selects text inside an EPUB, expose three distinct contextual
actions:

1. `Dictionary` opens the platform dictionary path.
2. `Translate` opens the platform translation path.
3. `Copy` copies the selected text.

After any action is dismissed, the same book and reading position must remain
active. No definition or translation is rendered or persisted by the app.

## Ownership And Public API

This is a platform-owned reader feature.

- Do not change `ReaderComponent`, shared models, Decompose state, SQL,
  preprocessing, or `commonMain`.
- Keep Android framework and Readium Kotlin types in `app-android`.
- Keep UIKit and Readium Swift Toolkit types in `app-ios-swift`.
- Add no analytics, result callbacks, selected-text persistence, chosen-app
  persistence, or clipboard persistence.

Shared public API changes: none.

## Existing Baseline

Android currently uses SDK 35 with min SDK 24 and Readium Kotlin 3.1.2.
`DefaultAndroidReaderComponent` creates the `EpubNavigatorFragment` factory and
stores the attached navigator. `ExternalTextActionLauncher` already implements
`ACTION_DEFINE`, `ACTION_TRANSLATE`, and read-only `ACTION_PROCESS_TEXT` for
Frequent words. The manifest already contains the required package-visibility
queries.

iOS currently targets iOS 15.2 and pins Readium Swift Toolkit 3.8.0.
`IosEpubReaderViewController` owns and embeds `EPUBNavigatorViewController`.
The Frequent words overlay already has separate UIKit wrappers for dictionary
and activity-sheet presentation; they do not need to change for this task.

Neither reader currently configures custom selected-text actions.

## Android Implementation

### Reuse the external text launcher

Extend
`app-android/src/main/kotlin/com/example/myapplication/android/ui/reader/ExternalTextActionLauncher.kt`
with this additional internal entry point:

```kotlin
fun launch(
    activity: Activity,
    text: String,
    action: ExternalTextAction,
)
```

The overload must trim `text` and return without side effects when the result
is empty. The existing `ReaderWordItem` overload must resolve its current
display-word/lemma action text and delegate to the raw-text overload. Use these
launch rules:

- Dictionary: query `ACTION_DEFINE` with `EXTRA_TEXT` on API 29+ and read-only
  `ACTION_PROCESS_TEXT`, deduplicate by package while preferring Define, then
  require an explicit choice from the app-owned picker unless the user already
  checked `Always use this app` for a still-valid package;
- Translate: `ACTION_TRANSLATE` with `EXTRA_TEXT` on API 29+, then read-only
  `ACTION_PROCESS_TEXT`;
- no Dictionary handler: `No dictionary app found`.

Persist only the remembered Dictionary package name in a dedicated Android
private preference file. The preference is global, has no time expiry, and is
cleared when the package is absent from current discovery or explicit launch
fails. Rebuild the target intent on every use so Define remains preferred after
app updates; do not persist the activity component or action. Do not add an
in-app reset control; a still-valid choice otherwise remains until application
data is cleared.

The launcher must require a live `Activity`. Do not add
`FLAG_ACTIVITY_NEW_TASK` or fall back to an application context, because that
can detach the external flow from the reader task.

Do not add web URLs, vendor packages, `ACTION_SEND`, or additional manifest
queries.

### Add the Readium action-mode callback

Create
`app-android/src/main/kotlin/com/example/myapplication/android/ui/reader/ReaderSelectionActionModeCallback.kt`
as an internal `ActionMode.Callback` with this constructor contract:

```kotlin
internal class ReaderSelectionActionModeCallback(
    private val applicationContext: Context,
    private val scope: CoroutineScope,
    private val navigatorProvider: () -> EpubNavigatorFragment?,
) : ActionMode.Callback
```

The callback must:

- clear the supplied menu in `onCreateActionMode()` and add, in order,
  `Dictionary`, `Translate`, and `Copy` using private stable integer IDs;
- mark each item with `MenuItem.SHOW_AS_ACTION_IF_ROOM` so all commands remain
  available without forcing labels to overflow the toolbar;
- return `false` from `onPrepareActionMode()`;
- while the action mode is active, register a higher-priority Activity Back
  callback so the first Back dismisses the selection instead of popping the
  reader; remove that callback in `onDestroyActionMode()`;
- return `false` for unknown menu IDs;
- return `true` immediately for a recognized action and launch the work in the
  supplied main coroutine scope;
- retrieve text before finishing the action mode with
  `navigator.currentSelection()?.locator?.text?.highlight?.trim()`;
- use `applicationContext` for clipboard and error presentation, but require
  `navigator.activity` for chooser presentation;
- ignore blank or missing selections;
- call the raw-text `ExternalTextActionLauncher` overload for Dictionary and
  Translate;
- implement Copy with
  `ClipboardManager.setPrimaryClip(ClipData.newPlainText("Selected text", text))`;
- call `navigator.clearSelection()` and `mode.finish()` after a recognized
  action, including blank-selection and failure paths, using `finally` so the
  contextual UI cannot remain stuck;
- for Dictionary and Translate, clear the selection and finish the action mode
  before launching the external chooser.

Do not finish the action mode before `currentSelection()` completes: Readium's
selection lookup is suspending and the selection may disappear when the mode
is closed.

### Attach the callback

In `DefaultAndroidReaderComponent.ReaderRuntime`, create one callback owned by
the runtime and pass it to:

```kotlin
EpubNavigatorFragment.Configuration(
    shouldApplyInsetsPadding = false,
    selectionActionModeCallback = selectionActionModeCallback,
)
```

Use the runtime's existing main `scope` and its current nullable `navigator`
as the provider. Do not change `ReadiumNavigatorHost`, fragment attachment, or
the shared reader contract.

The runtime already cancels its scope in `onDestroy()`, which also cancels
pending selection action work.

## iOS Implementation

### Configure the Readium editing actions

In
`app-ios-swift/app-ios-swift/reader-platform/IosEpubReaderViewController.swift`,
configure `EPUBNavigatorViewController` with exactly these app-requested
actions:

```swift
editingActions: [
    .copy,
    EditingAction(
        title: "Dictionary",
        action: #selector(openDictionaryForSelection)
    ),
    .translate,
]
```

Keep the existing paginated `EPUBPreferences(scroll: false)` setting. Do not
include `.lookup` or `.share`: `.lookup` can also add Search Web on iOS 16+,
while the product requires a separate Dictionary command.

Readium `.copy` must remain the Copy implementation because it enforces the
publication's copy rights. Do not replace it with a direct
`UIPasteboard.general.string` write.

Readium `.translate` must remain the Translate implementation for the iOS 15.2
deployment range. Do not invoke `translate:`, `_translate:`, or other private
selectors directly. Do not add an iOS 18-only Translation framework dependency
in this version.

### Implement Dictionary

Add this responder-chain selector to `IosEpubReaderViewController`:

```swift
@objc private func openDictionaryForSelection() {
    guard
        presentedViewController == nil,
        let term = navigator.currentSelection?
            .locator
            .text
            .highlight?
            .trimmingCharacters(in: .whitespacesAndNewlines),
        !term.isEmpty
    else {
        return
    }

    let controller = UIReferenceLibraryViewController(term: term)
    navigator.clearSelection()
    present(controller, animated: true)
}
```

Do not hide Dictionary by calling
`UIReferenceLibraryViewController.dictionaryHasDefinition(forTerm:)`; the menu
contract requires a stable Dictionary command, and the system reference view
owns its no-definition presentation.

Do not change the SwiftUI `ReferenceLibrarySheet` or `ActivityShareSheet` used
by Frequent words. Selected-text Dictionary is presented directly by the
UIKit reader controller, while selected-text Translate remains the native
Readium/iOS action.

## Failure And Compatibility Rules

- Trim only leading and trailing whitespace. Preserve punctuation, casing,
  spaces, and line breaks inside the selected text.
- Ignore a selection that is `null`, `nil`, or empty after trimming.
- Multi-word selections are allowed; do not perform lemmatization or language
  detection.
- Android API 24-28 skips the API 29-specific primary actions and uses
  `ACTION_PROCESS_TEXT`.
- Android must not crash when the fragment detaches while a menu action is
  pending.
- iOS can suppress native Translate when the OS cannot perform that command;
  do not replace it with an undocumented selector.
- iOS may add accessibility editing commands. The required product commands
  remain Copy, Dictionary, and Translate.
- Dictionary and translation surfaces return no result to the app.
- Dismissal must not recreate the reader, change its locator, close the Words
  overlay state, or alter the book library.

## Documentation Updates During Implementation

After code implementation, update the platform domain docs without copying the
full research notes:

- in `docs/domains/android-product-app.md`, record that the Android reader
  runtime installs Readium's `selectionActionModeCallback` and owns selected
  text Dictionary, Translate, and Copy adaptation;
- in `docs/domains/ios-swift-product-app.md`, record that
  `IosEpubReaderViewController` configures Readium editing actions and presents
  the reference-library controller for Dictionary.

Do not change shared reader-domain ownership because no shared contract is
introduced.

## Verification

### Automated build checks

Run:

```bash
./gradlew :app-android:assembleDebug
```

Then run the documented iOS simulator build:

```bash
xcodebuild -project app-ios-swift/app-ios-swift.xcodeproj -scheme app-ios-swift -configuration Debug -destination 'platform=iOS Simulator,id=806717FC-839D-461B-B4A0-DAEE93A128B8' -derivedDataPath app-ios-swift/build build
```

No shared tests are required because shared code must not change. Do not add a
new Android or iOS test framework solely for this platform integration.

### Android smoke checks

- Long-press an EPUB word and confirm Dictionary, Translate, and Copy are
  available in the contextual action mode.
- Dictionary shows one app-owned picker containing unique definition and
  read-only text-processor handler packages.
- Dictionary never launches Google or another sole handler until the user taps
  its picker row; it may launch that package directly on later requests only
  when `Always use this app` was checked.
- An unchecked selection remains one-time, Cancel persists nothing, and an
  unavailable remembered package is cleared without closing the reader.
- Translate tries a translation handler and falls back to the read-only text
  processor chooser.
- A device with no compatible handler shows the existing neutral message.
- Copy places the exact trimmed selection on the clipboard.
- Multi-word selection and mixed-case text are passed unchanged internally.
- Dismissing a chooser returns to the same page and reading position.
- Repeated selection after returning still displays a working menu.
- Back navigation and the Frequent words actions continue to work.

Where available, smoke API 28 to exercise the process-text-only path and API
29+ to exercise the primary intent path. Report an unavailable runtime rather
than substituting a vendor-specific handler.

### iOS smoke checks

- Long-press an EPUB word and confirm Copy, Dictionary, and Translate are
  separate menu commands on a simulator/device where system Translate is
  available.
- Dictionary presents `UIReferenceLibraryViewController`; dismissing it returns
  to the same page and reading position.
- Translate opens the native iOS translation surface.
- Copy places the selected text on the pasteboard for an unprotected EPUB.
- Multi-word and mixed-case selections remain unchanged apart from outer
  whitespace trimming in Dictionary.
- Repeated selection after dismissing Dictionary or Translate still works.
- Search highlighting, page navigation, reader chrome, and Frequent words
  actions remain functional.

Use the documented iOS 18.4 simulator by default. Smoke the iOS 15.x runtime if
it is installed; otherwise report that compatibility as build-verified but not
runtime-verified.

## Acceptance Criteria

- EPUB selection uses platform-native handles and contextual presentation on
  both platforms.
- Android exposes app-owned Dictionary, Translate, and Copy commands through
  Readium's supported callback.
- iOS configures Readium with `.copy`, a distinct custom Dictionary action,
  and native `.translate`.
- Dictionary and Translate are separate commands and invoke different platform
  paths.
- Copy works without bypassing Readium copy rights on iOS.
- Missing text or handlers never crashes or launches an empty surface.
- Reader position and current book survive every external/system presentation.
- Existing Frequent words Dictionary and Translate actions are unchanged.
- No shared API, persistence, preprocessing, SQL, analytics, or result model is
  added.
