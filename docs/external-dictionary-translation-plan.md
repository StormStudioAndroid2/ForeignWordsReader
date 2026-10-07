# External Dictionary and Translation Plan

## Status

Product and implementation plan. No application code is introduced by this
document.

## Goal

Let a reader hand a selected or listed word to system dictionary and
translation capabilities without building dictionary or translation result
screens inside ForeignWordsReader.

The feature has two entry points:

- a selected word in the reader;
- each item in the reader-scoped `Frequent words` overlay.

ForeignWordsReader only initiates the handoff. It does not fetch, render,
persist, or synchronize dictionary definitions or translations.

## Product Decision

Use native platform surfaces and installed applications instead of a custom
lookup popup.

- In the reader, preserve the native text-selection menu.
- In `Frequent words`, show two compact secondary actions below each word:
  `Dictionary` and `Translate`.
- Keep the current book and reader position intact while an external or system
  surface is open.
- Return through the standard dismiss, Back, or app-switching behavior of the
  operating system.

This approach keeps the feature small, gives users access to their preferred
tools, and avoids owning dictionary content, language support, accounts, or
translation quality.

## User Experience

### Reader selection

The reader must continue to use the platform-native text selection UI. The app
must not replace it with a custom popup.

- Dictionary action: open the platform dictionary experience or an installed
  text-processing application.
- Translate action: open the platform translation experience or an installed
  text-processing application.
- Closing the system surface must leave the user at the same reading position.

### Frequent words

Each word row shows the current word information followed by two compact
actions:

- `Dictionary`, with the platform book icon when available;
- `Translate`, with the platform translation or language icon when available.

The actions are visually secondary. They must not compete with opening or
closing the Words overlay and must not make the whole word row tappable.

Use `displayWord` as the action text after trimming whitespace. Fall back to
`lemma` only when `displayWord` is empty.

## iOS User Flow

### Selected word in the reader

1. The user selects a word in the EPUB content.
2. iOS presents the native edit menu supplied by the reader and UIKit.
3. The user chooses the system `Look Up` action for a dictionary definition or
   `Translate` for system translation.
4. iOS presents its own dictionary or translation UI over the reader.
5. The user dismisses that UI and remains at the same reader position.

The implementation must preserve the standard iOS menu items. It should not
duplicate `Look Up` or `Translate` when UIKit already provides them.

### Word in the Words overlay

1. The user opens `Frequent words`.
2. Tapping `Dictionary` presents `UIReferenceLibraryViewController` for the
   resolved action text.
3. Tapping `Translate` presents `UIActivityViewController` with the resolved
   action text.
4. The user chooses an eligible installed translation or text-processing share
   extension.
5. Dismissing the presented UI returns to the same Words list and scroll
   position.

The share sheet provides system actions such as Copy as a natural fallback.
If the requested presentation cannot be created, the app copies the word and
shows a neutral message.

### iOS platform constraint

iOS does not expose a universal public API that programmatically opens any
translator application with arbitrary text. `UIActivityViewController` can
offer compatible share extensions, but the presence of Apple Translate or any
specific third-party translator is not guaranteed. The native selection menu
is the reliable path to Apple's system `Translate` action inside the reader.

## Android User Flow

### Selected word in the reader

1. The user long-presses and selects a word in the EPUB content.
2. Android presents the native floating selection toolbar or contextual action
   mode supplied by the reader runtime.
3. The user chooses `Dictionary` or `Translate`.
4. For Dictionary, ForeignWordsReader queries both `Intent.ACTION_DEFINE` and
   read-only `Intent.ACTION_PROCESS_TEXT` handlers, deduplicates them by
   package while preferring Define, and presents an app-owned picker. Even a
   single handler requires an explicit user tap unless the user previously
   checked `Always use this app` for a still-valid package.
5. For Translate, ForeignWordsReader tries `Intent.ACTION_TRANSLATE` and falls
   back to read-only `Intent.ACTION_PROCESS_TEXT` when no specific handler is
   installed.
6. The user explicitly selects an installed dictionary, translator, or
   compatible text-processing application. A checked Dictionary choice is
   reused globally without a time expiry and is rediscovered before each use.
7. Android Back returns to the same reader position.

Reader menu integration must use a supported Readium or Android text-selection
extension point. It must not depend on private WebView internals. If the current
Readium version exposes no supported way to add host actions, the implementation
must preserve the native selection menu and treat custom reader menu items as a
separate compatibility task; the Words overlay actions remain deliverable.

### Word in the Words overlay

1. The user opens `Frequent words`.
2. Tapping `Dictionary` presents the combined Define and Process Text app
   picker for the row's resolved action text, or directly opens a valid package
   that the user explicitly chose with `Always use this app`.
3. Tapping `Translate` creates an `ACTION_TRANSLATE` intent for the row's
   resolved action text.
4. Translate falls back to the read-only `ACTION_PROCESS_TEXT` chooser when no
   action-specific handler exists.
5. If no activity can handle Dictionary, the app shows:
   `No dictionary app found`.
6. Android Back returns to the same Words list and scroll position.

### Android platform constraint

Dictionary combines `ACTION_DEFINE` with `ACTION_PROCESS_TEXT` because many
installed dictionary apps only expose the broader text-processing action.
`ACTION_TRANSLATE` remains the preferred translation handoff, with
`ACTION_PROCESS_TEXT` as its fallback. Package-specific intents or vendor deep
links are intentionally out of scope.

## Architecture And Ownership

The reader/search shared domain continues to own the Words overlay state and
`ReaderWordItem` data. It must remain platform neutral.

Android platform code owns:

- Compose rendering of the two row actions;
- combined Dictionary handler discovery and app-picker presentation;
- `ACTION_TRANSLATE` and fallback `ACTION_PROCESS_TEXT` construction and
  capability checks;
- explicit target launch and Toast/Snackbar fallback;
- any supported Readium/Android selection-menu adaptation.

iOS platform code owns:

- SwiftUI rendering of the two row actions;
- `UIReferenceLibraryViewController` presentation;
- `UIActivityViewController` presentation and copy fallback;
- preservation of native Readium/UIKit selection-menu behavior.

No Android `Intent`, UIKit controller, Readium object, or other platform type
may enter `commonMain`. A shared contract change is unnecessary unless later
product requirements add platform-neutral action state or analytics events.

## Error And Edge Cases

- Resolve whitespace-only `displayWord` to the trimmed `lemma`.
- Ignore an action when both values are empty; do not launch an empty chooser.
- Keep the Words overlay model and list identity unchanged across external
  presentation so scroll restoration remains a platform UI concern.
- Do not expect a result payload from dictionary or translation applications.
- Do not persist the selected word, definition, translation, chosen app, or
  clipboard contents.
- Do not automatically install or recommend a specific third-party app.
- Preserve all native selection actions such as Copy and Select All.
- Localize button labels, chooser titles, empty-handler messages, and
  accessibility labels when application localization is introduced.

## Accessibility

- Both Words actions must have explicit accessible labels: `Dictionary` and
  `Translate`.
- Buttons must meet the platform minimum touch target even when their visual
  treatment is compact.
- Icons supplement text and do not replace it in the first version.
- Dynamic Type and Android font scaling must not clip labels or frequency
  values.
- Native modal and chooser presentation should retain standard focus and
  screen-reader behavior.

## Implementation Sequence

1. Add Android platform helpers that resolve action text, combine
   `ACTION_DEFINE` and read-only `ACTION_PROCESS_TEXT` handlers in an
   explicit-choice Dictionary picker, optionally persist and revalidate a
   preferred package, and preserve the existing Translate fallback flow.
2. Add Android `Dictionary` and `Translate` secondary buttons to each Words
   row and wire them to the helper.
3. Inspect the pinned Readium Kotlin version for a supported text-selection
   action extension point and add reader actions only through that API.
4. Add iOS wrappers for `UIReferenceLibraryViewController` and
   `UIActivityViewController`.
5. Add iOS `Dictionary` and `Translate` bordered/plain buttons to each Words
   row and preserve list state during presentation.
6. Verify that the Readium iOS selection menu still exposes system `Look Up`
   and `Translate` without custom menu replacement.
7. Add focused platform tests for action-text resolution and Android intent
   construction where those helpers can be isolated.
8. Run Android and iOS platform builds, followed by device/simulator smoke
   checks for selection, chooser presentation, dismissal, and position
   preservation.

## Acceptance Criteria

- Every loaded Words row on Android and iOS shows `Dictionary` and `Translate`
  as compact secondary actions.
- Both actions use trimmed `displayWord`, falling back to trimmed `lemma`.
- Android Dictionary presents one explicit-choice picker containing unique
  Define and read-only Process Text handler packages until the user explicitly
  approves a remembered package; unchecked choices remain one-time.
- Android Translate sends `ACTION_TRANSLATE` first and falls back to read-only
  `ACTION_PROCESS_TEXT`.
- Android reports a neutral error when no compatible handler exists.
- iOS Dictionary presents the system reference-library controller.
- iOS Translate presents the system activity sheet with the word as text.
- Native reader selection remains available and retains standard system menu
  actions on both platforms.
- Supported reader-level dictionary and translation actions do not replace the
  native menu or depend on private Readium/WebView APIs.
- Dismissing any system or external surface preserves the reader position or
  Words list position.
- ForeignWordsReader neither renders nor stores definitions or translations.

## Out Of Scope

- An in-app dictionary or translation result screen.
- Network calls to dictionary or translation APIs.
- Language detection, source/target language pickers, or translation settings.
- Pinning definitions or translations to a vocabulary list.
- Parsing results returned by another application.
- Vendor-specific Android packages, custom URL schemes, or deep links.
- Installing, ranking, or recommending dictionary and translator apps.

## Verification Plan

Android:

- build with `./gradlew :app-android:assembleDebug`;
- verify one device with a compatible handler and one state without a handler;
- verify reader selection, chooser cancellation, Back navigation, and Words
  scroll preservation.

iOS:

- build with the simulator `xcodebuild` runbook from
  `docs/domains/ios-swift-product-app.md`;
- verify native `Look Up` and `Translate` from selected EPUB text;
- verify system dictionary presentation from a Words row;
- verify activity-sheet presentation, cancellation, and Words scroll
  preservation.

Shared:

- no shared verification is required when implementation remains entirely in
  platform UI/runtime code;
- run `./gradlew :shared:test` if the shared reader contract changes.

## References

- Android `Intent`, `ACTION_DEFINE`, `ACTION_TRANSLATE`, and
  `ACTION_PROCESS_TEXT`:
  <https://developer.android.com/reference/android/content/Intent>
- Android implicit intents and chooser behavior:
  <https://developer.android.com/guide/components/intents-filters>
- Apple: translate selected text in apps:
  <https://support.apple.com/guide/iphone/translate-text-in-apps-iphab4dcff1d/ios>
- Apple `UIReferenceLibraryViewController`:
  <https://developer.apple.com/documentation/uikit/uireferencelibraryviewcontroller>
- Apple `UIActivityViewController`:
  <https://developer.apple.com/documentation/uikit/uiactivityviewcontroller>
