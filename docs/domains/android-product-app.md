# Android product app

This document describes the Android product app boundary: how Android connects
shared contracts to Android entrypoints, Compose UI, SAF permissions, Readium
Kotlin, platform storage, bundled assets, and debug-only platform tools.

It intentionally does not redefine shared component contracts. Android creates,
renders, and adapts shared components, while shared domains remain the source of
truth for navigation, library state, reader/search state, preprocessing,
database storage, and native UDPipe semantics.

## Responsibility

The Android product app is responsible for:

- launching the app through the Android `MainActivity`;
- creating the shared root component with Android platform dependencies;
- rendering root children with Android Compose;
- owning Android-only UI surfaces, Activity Result launchers, and Fragment
  hosting;
- acquiring SAF read and tree permissions for selected EPUB files or debug
  folders;
- opening and inspecting EPUB publications through Readium Kotlin;
- rendering EPUB content through Readium `EpubNavigatorFragment`;
- mapping Android/Readium callbacks into shared reader and search contracts;
- wiring Android storage, bundled assets, model repositories, and platform
  providers into shared domains;
- providing debug-only folder batch processing and debug export files;
- cleaning up Android runtime resources during lifecycle transitions.

The main source files are:

- `app-android/src/main/kotlin/com/example/myapplication/android/MainActivity.kt`
- `app-android/src/main/kotlin/com/example/myapplication/android/reader/AndroidBookLibraryGateway.kt`
- `app-android/src/main/kotlin/com/example/myapplication/android/reader/DefaultAndroidReaderComponent.kt`
- `app-android/src/main/kotlin/com/example/myapplication/android/ui/main/MainContent.kt`
- `app-android/src/main/kotlin/com/example/myapplication/android/ui/reader/ReaderContent.kt`
- `app-android/src/main/kotlin/com/example/myapplication/android/ui/reader/ReadiumNavigatorHost.kt`
- `shared/src/androidMain/kotlin/com/example/myapplication/shared/data/AndroidBookLibraryStoreFactory.kt`
- `shared/src/androidMain/kotlin/com/example/myapplication/shared/processing/AndroidGlobalFrequencyRepository.kt`
- `shared/src/androidMain/kotlin/com/example/myapplication/shared/processing/AndroidTextAnalysisProvider.kt`

## Out of scope

The Android product app does not own:

- Decompose root navigation semantics;
- shared `MainComponent` model and events;
- shared `ReaderComponent` model and events;
- shared `SearchComponent` state transitions;
- preprocessing stage order, fingerprinting, filtering, scoring, or persistence
  semantics;
- SQL schema and migration contracts;
- native UDPipe adapter ABI or model lifecycle semantics;
- global frequency database generation;
- iOS app behavior.

Those responsibilities are documented in the shared domain docs listed below.

## Shared delegation boundary

Android creates and wires shared components, but it should not duplicate or
change their contracts in Android-specific code.

Use these documents as the source of truth:

- root navigation: `docs/domains/app-architecture.md`;
- library contract: `docs/domains/library-domain.md`;
- reader and search shared contract:
  `docs/domains/reader-search-domain.md`;
- preprocessing pipeline: `docs/domains/book-preprocessing.md`;
- database and storage schema: `docs/domains/database-storage.md`;
- native UDPipe runtime: `docs/domains/native-udpipe-runtime.md`;
- global frequency build inputs: `docs/global-frequency-db.md`.

Android-specific code may adapt platform capabilities to those contracts. It
must not pass Android framework types, Readium Kotlin types, native handles, or
file-system details into `commonMain`.

## Entrypoint and root rendering

`MainActivity` is the Android launcher activity and product entrypoint.

It owns:

- creating `AndroidBookLibraryGateway`;
- creating `DefaultRootComponent` with `defaultComponentContext()`;
- providing `DefaultAndroidReaderComponent` through `ReaderComponentFactory`;
- passing `lastReadableEpubUriString(this)` as the startup reader restore value;
- installing the Compose content tree with `AndroidRootContent`;
- passing a debug batch processor only when `ApplicationInfo.FLAG_DEBUGGABLE`
  is set;
- removing the current Readium navigator fragment in `onSaveInstanceState`
  before delegating to the Android framework.

`AndroidRootContent` owns Android rendering branches for the current root child:

- `Child.Main` renders `AndroidMainContent`;
- `Child.Reader` renders `AndroidReaderContent`;
- `Child.Welcome` renders `AndroidWelcomeContent`.

The root stack, initial stack rules, and app-level back behavior are shared
root architecture responsibilities. Android should only render the selected
child and provide Android dependencies.

## Android build and manifest

The Android application module owns the installable Android artifact.

Current app-level build facts:

- namespace and application id:
  `com.example.myapplication.android`;
- app module depends on `:shared` and `:compose-ui`;
- UI uses Compose, Decompose Compose extensions, and AndroidX Fragment;
- EPUB import and rendering use Readium shared, streamer, and navigator
  artifacts;
- core library desugaring is enabled;
- release minification is currently disabled.

The Android manifest owns launcher configuration:

- `MainActivity` is the exported launcher activity;
- `allowBackup` is disabled;
- RTL support is enabled;
- the app theme is `@style/AppTheme`.

Do not document shared behavior here just because the Android build depends on
the shared module. Build files describe wiring, not ownership of shared domain
semantics.

## Agent device runbook

This operational runbook is for agents after Android-specific code changes. It
explains how to build, install, and launch the Android app without Android
Studio.

Current Android launch facts:

- Gradle module: `:app-android`;
- application id: `com.example.myapplication.android`;
- launcher activity: `com.example.myapplication.android/.MainActivity`;
- debug APK path:
  `app-android/build/outputs/apk/debug/app-android-debug.apk`;
- Android SDK platform tools path from the current `local.properties`:
  `/Users/sergeykudinov/Library/Android/sdk/platform-tools/adb`.

Use the physical-device flow by default:

```bash
/Users/sergeykudinov/Library/Android/sdk/platform-tools/adb devices -l
./gradlew :app-android:assembleDebug
/Users/sergeykudinov/Library/Android/sdk/platform-tools/adb -d install -r app-android/build/outputs/apk/debug/app-android-debug.apk
/Users/sergeykudinov/Library/Android/sdk/platform-tools/adb -d shell am force-stop com.example.myapplication.android
/Users/sergeykudinov/Library/Android/sdk/platform-tools/adb -d shell am start -n com.example.myapplication.android/.MainActivity
```

Use the emulator flow only when the target is an emulator:

```bash
/Users/sergeykudinov/Library/Android/sdk/platform-tools/adb devices -l
./gradlew :app-android:assembleDebug
/Users/sergeykudinov/Library/Android/sdk/platform-tools/adb -e install -r app-android/build/outputs/apk/debug/app-android-debug.apk
/Users/sergeykudinov/Library/Android/sdk/platform-tools/adb -e shell am force-stop com.example.myapplication.android
/Users/sergeykudinov/Library/Android/sdk/platform-tools/adb -e shell am start -n com.example.myapplication.android/.MainActivity
```

Safety rules:

- If `adb devices -l` does not show a target in the `device` state, stop and
  report the reason instead of trying to install.
- If the target is `unauthorized`, ask the user to approve USB debugging on the
  device.
- If more than one physical device is connected, do not guess the target.
  `adb -d` should fail in that case; report the ambiguity.
- Do not run `adb uninstall` automatically. It deletes the app data, including
  persisted books, locators, and debug state.
- `./gradlew :app-android:installDebug` is acceptable for Android Studio style
  workflows, but agent runs should prefer explicit `assembleDebug` plus
  `adb install -r` so build and device failures are easier to separate.

Post-run verification:

- A successful `am start` is the primary launch check.
- When the agent needs to confirm the process is running, use:

```bash
/Users/sergeykudinov/Library/Android/sdk/platform-tools/adb -d shell pidof com.example.myapplication.android
```

- Read logcat only when diagnosing a launch or runtime failure. Prefer filters
  for the package, activity startup, or Android runtime crash output instead of
  dumping unrelated device logs.

## Library Android surface

`AndroidMainContent` renders the library surface for Android.

It owns:

- subscribing to `MainComponent.Model` for Compose rendering;
- showing Android list, empty, loading, error, and processing-status UI;
- launching the EPUB picker with `ActivityResultContracts.OpenDocument`;
- passing selected EPUB URI strings to `MainComponent.onEpubSelected`;
- showing the debug folder picker with `ActivityResultContracts.OpenDocumentTree`
  when a debug processor is available;
- showing debug batch progress, completion, and error messages.

`AndroidMainContent` does not own library state transitions. It should keep
using `MainComponent` callbacks instead of directly importing books or opening
the reader.

## Android book library gateway

`AndroidBookLibraryGateway` adapts Android and Readium capabilities to the
shared `BookLibraryGateway` contract.

It owns Android-specific work:

- taking persistable read permission for EPUB content URIs;
- taking persistable tree read permission for debug folder URIs;
- resolving Android `Uri` values to Readium absolute URLs;
- retrieving assets through Readium `AssetRetriever`;
- opening publications through Readium `PublicationOpener`;
- reading publication metadata;
- creating a stable Android book id from the URI string;
- extracting and saving covers under `filesDir/book-covers`;
- converting Readium publication content into shared `TextSection` values;
- dispatching load/import/open callbacks from an Android coroutine scope;
- starting preprocessing with Android model, analysis, global-frequency, and
  store implementations;
- closing Android preprocessing providers and repositories after processing;
- exporting debug logs in debug builds.

It does not own:

- the `BookLibraryGateway` shared API shape;
- recent-book sorting semantics;
- processing status meaning;
- preprocessing stage order or fingerprinting;
- database schema;
- lemma score semantics.

When Android import behavior changes, update this document only for platform
responsibilities. Update `library-domain.md`, `book-preprocessing.md`, or
`database-storage.md` only when their shared contracts change.

## Reader Android surface

`AndroidReaderContent` renders the Android reader screen.

It owns:

- subscribing to shared `ReaderComponent.Model`;
- subscribing to shared `SearchComponent.Model`;
- requiring a `DefaultAndroidReaderComponent` for actual Android reader
  rendering;
- showing Android loading and error states;
- hosting `ReadiumNavigatorHost` when the Android reader model is ready;
- showing Android reader chrome over the Readium navigator;
- opening the Android search overlay from reader chrome;
- forwarding search dismiss and result-click events to the shared search
  component;
- translating progress slider changes into Android navigator seek requests.

If a passed `ReaderComponent` is not an Android reader component, Android shows
`UnsupportedReaderContent`. This protects the platform UI from assuming every
shared reader implementation can host a Readium Kotlin navigator.

`ReaderChromeOverlay` owns Android reader chrome presentation. Its current
Android controls include:

- search;
- disabled bookmark placeholder;
- disabled settings placeholder;
- disabled contents placeholder;
- progress label and slider.

Search state and result actions belong to the shared search component. The
Android overlay only renders and routes events.

## Android reader runtime

`DefaultAndroidReaderComponent` adapts the shared `ReaderComponent` contract to
Readium Kotlin.

It owns:

- keeping an Android reader runtime in Decompose `InstanceKeeper`;
- opening EPUB publications through Readium Kotlin;
- taking persistable read permission for the active EPUB URI;
- restoring a saved Readium locator from Android `SharedPreferences`;
- saving locator JSON when shared reader progress changes;
- storing the last opened readable EPUB URI for Android startup restore;
- building an `EpubNavigatorFragment` factory;
- disabling Readium navigator inset padding because root Compose already
  applies system bar insets;
- exposing `AndroidReaderModel` for Android UI rendering;
- attaching and detaching the current `EpubNavigatorFragment`;
- closing publication and search resources when the runtime is destroyed.

It does not own shared reader navigation semantics. `onBackClicked` delegates to
the root-provided finish callback, while `onLocatorChanged` updates shared model
progress and Android persistence.

Current parity note:

- Android `onPreviousClicked` and `onNextClicked` are no-ops.
- iOS has previous/next behavior.
- Any feature that depends on previous/next reader navigation must explicitly
  close this Android parity gap.

## Readium navigator host

`ReadiumNavigatorHost` embeds Readium's `EpubNavigatorFragment` into Compose.

It owns:

- creating a `FragmentContainerView` inside `AndroidView`;
- installing the Readium fragment through `FragmentManager`;
- waiting for the current navigator fragment to be available;
- attaching a `DirectionalNavigationAdapter`;
- toggling Android reader chrome on center taps;
- collecting `currentLocator` from the navigator;
- forwarding locator JSON, reading progress, and page position to the shared
  reader component;
- seeking to a progression when the Android progress slider emits a request;
- removing the navigator fragment when the composition is disposed and the
  FragmentManager state is not saved.

The host should remain Android-only. Shared code should never depend on
`FragmentManager`, `EpubNavigatorFragment`, `InputListener`, or Readium
navigator classes.

## Android search runtime

Android search is split between shared state and Android Readium execution.

Shared owns:

- search visibility;
- query text;
- loading, results, empty, and error states;
- pagination state;
- result selection;
- dismiss and clear behavior.

Android owns:

- calling Readium `Publication.search(query)`;
- holding and closing the Readium `SearchIterator`;
- converting Readium `Locator` values to shared `ReaderSearchResultItem`;
- parsing selected locator JSON back into a Readium `Locator`;
- navigating the current `EpubNavigatorFragment` to the result;
- applying highlight decorations through `DecorableNavigator`;
- clearing highlight decorations when search is cancelled.

The current Android result navigation intentionally uses a copy of the locator
with empty text for the jump, then renders the original locator as the selected
highlight. This avoids Readium's text-based scroll path leaving passages
vertically offset in the paginated viewport.

## Android storage and assets

Android owns platform file locations and asset installation. It does not own the
shared schema or processing semantics attached to those files.

Current Android-owned storage and asset wiring includes:

- book database creation through `AndroidBookLibraryStoreFactory`;
- cover files under `filesDir/book-covers`;
- debug lemma export files under `filesDir/debug-lemma-index`;
- reader locator and last-readable-URI values in `readium_reader`
  `SharedPreferences`;
- global frequency database asset copying through
  `AndroidGlobalFrequencyRepositoryFactory`;
- UDPipe model asset installation through `AndroidModelRepository`;
- native UDPipe execution through `AndroidTextAnalysisProvider`.

Use the storage, global-frequency, and native-runtime docs for schema,
generation, versioning, and ABI rules.

## Debug-only behavior

Android debug batch processing is a platform debugging tool.

It owns:

- showing the debug folder action only for debuggable builds;
- requesting folder access through Android's document tree picker;
- recursively listing EPUB files by MIME type or `.epub` extension;
- processing files in display-name order;
- importing each EPUB through the normal Android gateway path;
- forcing preprocessing for debug batch entries;
- reporting progress and completion to Android UI;
- writing processing logs and top-lemma exports;
- writing failure logs when a file cannot be processed.

Debug batch behavior is not part of the shared library contract. Shared
components should not know that this Android-only tool exists.

## Lifecycle rules

Android code must preserve these lifecycle boundaries:

- persisted URI permissions are best-effort; some providers can still allow
  current-session access without granting persistable access;
- `lastReadableEpubUriString(context)` must only return URIs with persisted read
  permission;
- `MainActivity.onSaveInstanceState` removes the current Readium navigator
  before saving state;
- reader runtime owns the active publication and must close it on destroy;
- search runtime owns the active Readium search iterator and must close it when
  search is cancelled or the runtime is destroyed;
- preprocessing startup must close `AndroidTextAnalysisProvider` and
  `AndroidGlobalFrequencyRepository` after processing;
- Android framework, Readium, native handle, and file-system objects must not
  escape into `commonMain`.

## Change playbook

### Android-only library UI affordance

For UI that only changes Android presentation, update Android Compose code and
this document if ownership changes. Do not update shared domain docs unless the
feature needs new `MainComponent` state or callbacks.

Examples:

- changing list row layout;
- changing the EPUB picker button;
- changing debug status copy;
- adding an Android-only visual treatment for processing status.

### EPUB import behavior

For Android import changes, decide which boundary owns the change:

- SAF permission, URI handling, Readium opening, metadata fallback, cover file
  saving, or text-section extraction belong here;
- `BookItem` fields, library callbacks, recent ordering, or processing status
  mapping belong to `library-domain.md`;
- preprocessing decisions belong to `book-preprocessing.md`;
- schema or transaction behavior belongs to `database-storage.md`.

### Android reader chrome or overlay

If the feature is pure Android presentation over the active reader, keep it in
Android reader UI.

If it has shared visibility, async state, errors, results, or business rules,
add a reader subcomponent in the shared reader domain and render it from Android
Compose.

If it requires Readium `Publication`, `EpubNavigatorFragment`, locator
navigation, selection, decorations, or Android text APIs, add or extend an
Android gateway behind the shared reader contract. Do not expose Readium types
to `commonMain`.

### Readium navigator/runtime behavior

Changes to fragment hosting, locator collection, progression seeking, center
tap handling, search highlighting, or publication lifecycle belong here.

When these changes alter shared reader behavior, also update
`reader-search-domain.md`.

### Debug batch/export behavior

Debug batch is Android-only. Keep UI, folder traversal, forced processing, and
debug file exports in Android code.

If debug processing requires new preprocessing facts, update
`book-preprocessing.md`. If it only changes Android export shape or folder
behavior, update this document.

### Android storage, assets, or native wiring

Android file paths, asset copy locations, and platform repository/provider
wiring belong here.

Schema changes belong to `database-storage.md`. UDPipe adapter, model version,
provider lifecycle, and native build changes belong to
`native-udpipe-runtime.md`.

## Verification

Use `docs/agent-harness.md` for the full verification matrix.

For Android-specific code changes, the default build check is:

```bash
./gradlew :app-android:assembleDebug
```

Use the `Agent device runbook` in this document when a change needs real
install and launch verification, such as Activity startup, Compose rendering,
Readium navigator hosting, SAF permission handling, Android search runtime,
asset/model installation, or lifecycle cleanup.

Shared component behavior belongs to the shared domain tests, usually:

```bash
./gradlew :shared:test
```

Run Android unit or instrumentation tests only when they protect Android
adaptation behavior that cannot be covered by shared tests.

## Coverage expectations

No app tests are required for documentation-only changes to this file.

Android coverage should be change-driven. Do not add native, instrumentation, or
gateway tests simply because this document exists.

When Android platform behavior changes, add focused coverage where it controls
development risk:

- extractable gateway helper tests for fallback title, stable id generation,
  cover path preservation, or text-section splitting;
- smoke or instrumentation coverage for real Readium import, rendering, search,
  or result navigation changes;
- lifecycle checks when changing navigator attachment, locator persistence,
  search cancellation, publication closing, or provider cleanup;
- debug smoke coverage when changing debug folder traversal, forced processing,
  or debug export files.

Shared component tests belong with the shared domain that owns the contract.
Android tests should validate Android adaptation, not duplicate shared state
machine tests.
