# iOS Swift product app

This document describes the iOS Swift product app boundary: how the SwiftUI app
connects shared contracts to iOS entrypoints, SwiftUI rendering, Decompose Swift
helpers, file import, security-scoped bookmarks, Readium Swift Toolkit,
platform storage, bundled assets, and iOS runtime lifecycle.

It intentionally does not redefine shared component contracts. iOS creates,
renders, and adapts shared components, while shared domains remain the source of
truth for navigation, library state, reader/search state, preprocessing,
database storage, and native UDPipe semantics.

## Responsibility

The iOS Swift product app is responsible for:

- launching the app through the SwiftUI `iOSApp` entrypoint;
- creating the shared root component with iOS platform dependencies;
- rendering root children with SwiftUI;
- bridging Decompose `Value` and `ChildStack` values into SwiftUI state and
  navigation;
- owning iOS-only UI surfaces, file import, and UIKit view controller hosting;
- saving security-scoped bookmarks for selected EPUB files;
- opening and inspecting EPUB publications through Readium Swift Toolkit;
- rendering EPUB content through `EPUBNavigatorViewController`;
- mapping iOS/Readium callbacks into shared reader and search contracts;
- wiring iOS storage, bundled assets, model repositories, and platform
  providers into shared domains;
- providing debug-only lemma export files;
- cleaning up iOS runtime resources during lifecycle transitions.

The main source files are:

- `app-ios-swift/app-ios-swift/iOSApp.swift`
- `app-ios-swift/app-ios-swift/RootView.swift`
- `app-ios-swift/app-ios-swift/MainView.swift`
- `app-ios-swift/app-ios-swift/reader-platform/IosBookLibraryGateway.swift`
- `app-ios-swift/app-ios-swift/reader-platform/DefaultIosReaderComponent.swift`
- `app-ios-swift/app-ios-swift/reader-platform/IosReaderRuntime.swift`
- `app-ios-swift/app-ios-swift/reader-platform/IosEpubReaderViewController.swift`
- `app-ios-swift/app-ios-swift/reader-platform/IosReaderSearchGateway.swift`
- `shared/src/iosMain/kotlin/com/example/myapplication/shared/data/IosBookLibraryStoreFactory.kt`
- `shared/src/iosMain/kotlin/com/example/myapplication/shared/processing/IosGlobalFrequencyRepository.kt`
- `shared/src/iosMain/kotlin/com/example/myapplication/shared/processing/IosTextAnalysisProvider.kt`

## Out of scope

The iOS Swift product app does not own:

- Decompose root navigation semantics;
- shared `MainComponent` model and events;
- shared `ReaderComponent` model and events;
- shared `SearchComponent` state transitions;
- preprocessing stage order, fingerprinting, filtering, scoring, or persistence
  semantics;
- SQL schema and migration contracts;
- native UDPipe adapter ABI or model lifecycle semantics;
- global frequency database generation;
- Android app behavior.

Those responsibilities are documented in the shared domain docs listed below.

## Shared delegation boundary

iOS creates and renders shared components, but it should not duplicate or
change their contracts in Swift-specific code.

Use these documents as the source of truth:

- root navigation: `docs/domains/app-architecture.md`;
- library contract: `docs/domains/library-domain.md`;
- reader and search shared contract:
  `docs/domains/reader-search-domain.md`;
- preprocessing pipeline: `docs/domains/book-preprocessing.md`;
- database and storage schema: `docs/domains/database-storage.md`;
- native UDPipe runtime: `docs/domains/native-udpipe-runtime.md`;
- global frequency build inputs: `docs/global-frequency-db.md`.

iOS-specific code may adapt platform capabilities to those contracts. It must
not pass SwiftUI, UIKit, Readium Swift Toolkit, native handles, security-scoped
URL access, or file-system details into `commonMain`.

## Entrypoint and root rendering

`iOSApp` is the SwiftUI application entrypoint.

`AppDelegate` owns root creation. It provides:

- `DefaultRootComponent`;
- `DefaultComponentContext(lifecycle: ApplicationLifecycle())`;
- `IosBookLibraryGateway`;
- `IosReaderComponentFactory`;
- `IosReaderPersistence.lastReadableEpubUriString()` as the startup reader
  restore value.

`RootView` owns iOS rendering branches for the current root child:

- `RootComponentChild.Main` renders `MainView`;
- `RootComponentChild.Reader` renders `ReaderView`;
- `RootComponentChild.Welcome` renders `WelcomeView`.

`StackView` and the other Decompose helper files bridge shared Decompose state
into SwiftUI navigation:

- `StateValue` observes shared `Value` instances;
- `StackView` maps a shared `ChildStack` to `NavigationStack` on iOS 16.1+;
- `StackInteropView` provides a `UINavigationController` fallback for older
  supported iOS versions.

The root stack, initial stack rules, and app-level back behavior are shared root
architecture responsibilities. iOS should only render the selected child and
provide iOS dependencies.

## iOS build and project

The current product iOS app is the SwiftUI project under `app-ios-swift`.

Current app-level build facts:

- Xcode project: `app-ios-swift/app-ios-swift.xcodeproj`;
- scheme and target: `app-ios-swift`;
- bundle id: `orgIdentifier.app-ios-swift`;
- deployment target: `15.2`;
- app product: `app-ios-swift.app`;
- Swift package dependencies include Readium Swift Toolkit modules such as
  `ReadiumShared`, `ReadiumStreamer`, and `ReadiumNavigator`;
- bundled global frequency database:
  `app-ios-swift/app-ios-swift/Resources/frequency/global-frequency.sqlite`;
- bundled UDPipe model:
  `app-ios-swift/app-ios-swift/Resources/udpipe/english-ewt.udpipe`;
- the Xcode project runs
  `./gradlew :shared:embedAndSignAppleFrameworkForXcode` to embed the shared
  Kotlin framework.

`app-ios-compose` exists in the repository, but this document covers the
current SwiftUI product app in `app-ios-swift`.

## Agent simulator runbook

This operational runbook is for agents after iOS-specific code changes. It
explains how to build, install, and launch the iOS Swift app on a simulator
without using the Xcode UI.

Current iOS launch facts:

- scheme: `app-ios-swift`;
- project: `app-ios-swift/app-ios-swift.xcodeproj`;
- bundle id: `orgIdentifier.app-ios-swift`;
- deterministic derived data path: `app-ios-swift/build`;
- built simulator app path:
  `app-ios-swift/build/Build/Products/Debug-iphonesimulator/app-ios-swift.app`;
- default simulator target: `iPhone 16 Pro` on iOS `18.4`;
- default simulator UDID: `806717FC-839D-461B-B4A0-DAEE93A128B8`.

Use this simulator flow by default:

```bash
xcrun simctl list devices available
xcrun simctl boot 806717FC-839D-461B-B4A0-DAEE93A128B8
xcrun simctl bootstatus 806717FC-839D-461B-B4A0-DAEE93A128B8 -b
xcodebuild -project app-ios-swift/app-ios-swift.xcodeproj -scheme app-ios-swift -configuration Debug -destination 'platform=iOS Simulator,id=806717FC-839D-461B-B4A0-DAEE93A128B8' -derivedDataPath app-ios-swift/build build
xcrun simctl install 806717FC-839D-461B-B4A0-DAEE93A128B8 app-ios-swift/build/Build/Products/Debug-iphonesimulator/app-ios-swift.app
xcrun simctl launch 806717FC-839D-461B-B4A0-DAEE93A128B8 orgIdentifier.app-ios-swift
```

Safety rules:

- If the default simulator is unavailable, run
  `xcrun simctl list devices available` and report the available targets
  instead of guessing.
- Do not run `xcrun simctl erase` automatically. It deletes app data,
  including imported books, bookmarks, locators, installed assets, and debug
  files.
- Do not uninstall automatically unless the user explicitly asks for a clean
  install. Prefer reinstalling the built app while preserving simulator data.
- Always pass `-derivedDataPath app-ios-swift/build` to `xcodebuild` so the
  `.app` path is deterministic.
- Opening the Simulator UI with `open -a Simulator` is optional and should only
  happen when visual inspection is requested.
- Physical iOS device install is not the default. Current discovery showed no
  connected iPhone or iPad device. To inspect physical devices, run
  `xcrun xctrace list devices` and stop if no real iOS device is present.

Post-run verification:

- A successful `simctl launch` is the primary launch check.
- When diagnosing launch failures, capture focused logs for the simulator and
  bundle id instead of dumping unrelated device logs.
- When visual verification is required, open Simulator UI only after the build,
  install, and launch commands succeed.

## Library iOS surface

`MainView` renders the library surface for iOS.

It owns:

- observing `MainComponent.Model` through `StateValue`;
- showing iOS list, empty, loading, error, and processing-status UI;
- presenting SwiftUI `fileImporter`;
- accepting EPUB and data file types through `UTType.epub` and `.data`;
- saving a security-scoped bookmark through `IosReaderPersistence` before
  calling the shared component;
- passing selected EPUB URI strings to `MainComponent.onEpubSelected`;
- rendering book covers through `BookCoverImageLoader`.

`MainView` does not own library state transitions. It should keep using
`MainComponent` callbacks instead of directly importing books or opening the
reader.

`BookCoverImageLoader` owns iOS cover loading. It checks explicit file URLs,
relative `Application Support` cover paths, and the default `BookCovers`
location, then caches loaded `UIImage` values in memory.

## iOS book library gateway

`IosBookLibraryGateway` adapts iOS and Readium Swift Toolkit capabilities to
the shared `BookLibraryGateway` contract.

It owns iOS-specific work:

- resolving selected EPUB URI strings through `IosReaderPersistence`;
- starting and stopping security-scoped URL access;
- opening EPUB files with Readium `AssetRetriever` and `PublicationOpener`;
- reading publication metadata;
- creating a stable iOS book id from the URI string;
- extracting and saving covers under Application Support `BookCovers`;
- converting Readium publication content into shared `TextSection` values;
- dispatching import/open callbacks to the main actor;
- starting preprocessing with iOS model, analysis, global-frequency, and store
  implementations;
- exporting debug lemma files in debug builds.

It does not own:

- the `BookLibraryGateway` shared API shape;
- recent-book sorting semantics;
- processing status meaning;
- preprocessing stage order or fingerprinting;
- database schema;
- lemma score semantics.

Current iOS note:

- `IosBookLibraryGateway` has local constants for analysis language, provider,
  model, index version, and pipeline fingerprint.
- The source of truth for those semantics is `docs/domains/book-preprocessing.md`.
- If these constants change, verify they stay aligned with shared preprocessing
  constants and update the preprocessing domain doc when the shared contract
  changes.

## Reader iOS surface

`ReaderView` renders the iOS reader screen.

It owns:

- observing shared `ReaderComponent.Model`;
- observing shared `SearchComponent.Model`;
- observing shared `WordsComponent.Model`;
- observing `IosReaderState` for iOS runtime phase;
- showing SwiftUI loading and error states;
- hosting `IosEpubReaderViewController` through `EpubNavigatorContainer`;
- showing iOS reader chrome over the Readium navigator;
- opening the iOS search overlay from reader chrome;
- opening the iOS words overlay from reader chrome;
- forwarding search dismiss, query, submit, clear, load-more, and result-click
  events to the shared search component;
- forwarding words dismiss events to the shared words component;
- translating progress slider changes into iOS reader navigation requests.

`ReaderChromeOverlay` owns iOS reader chrome presentation. Its current controls
include:

- search;
- words;
- disabled contents placeholder;
- disabled settings placeholder;
- progress label and slider.

Search state and result actions belong to the shared search component. The iOS
words modal state belongs to the shared words component. The iOS overlay only
renders and routes events. Bookmarks should be added later as a tab inside
Contents rather than returning as a top-level chrome action.

## iOS reader runtime

`DefaultIosReaderComponent` adapts the shared `ReaderComponent` contract to
Readium Swift Toolkit.

It owns:

- creating `IosReaderRuntime`;
- exposing `IosReaderState` for SwiftUI rendering;
- creating the shared `DefaultSearchComponent` with the iOS search gateway;
- creating the shared `DefaultWordsComponent` with the iOS words gateway;
- closing the iOS runtime when the reader component is deallocated or closed;
- delegating `onBackClicked` to the root-provided finish callback;
- implementing `onPreviousClicked` and `onNextClicked` through the iOS runtime;
- saving locator JSON through `IosReaderPersistence`.

`IosReaderRuntime` owns:

- resolving security-scoped EPUB URLs;
- opening Readium publications;
- restoring the saved locator;
- creating `IosEpubReaderViewController`;
- saving the last readable EPUB URI;
- updating the search gateway with the active publication and reader;
- throttling locator persistence by progress delta and time interval;
- forcing pending locator persistence when the app enters background;
- stopping security-scoped access when closed;
- invalidating search and resetting reader state when closed.

`IosEpubReaderViewController` owns UIKit/Readium navigator behavior:

- embedding `EPUBNavigatorViewController`;
- setting paginated EPUB preferences with `scroll: false`;
- binding `DirectionalNavigationAdapter`;
- forwarding location changes to the iOS runtime;
- implementing previous and next page navigation;
- seeking to progress;
- toggling reader chrome on center taps;
- applying and clearing search highlight decorations;
- reporting Readium navigator errors to `NSLog`.

The runtime should remain iOS-only. Shared code should never depend on
SwiftUI, UIKit, `EPUBNavigatorViewController`, `Publication`, `Locator`,
security-scoped URL access, or Readium navigator classes.

## iOS search runtime

iOS search is split between shared state and iOS Readium execution.

Shared owns:

- search visibility;
- query text;
- loading, results, empty, and error states;
- pagination state;
- result selection;
- dismiss and clear behavior.

iOS owns:

- calling Readium `Publication.search(query:)`;
- holding and closing the Readium `SearchIterator`;
- converting Readium `Locator` values to shared `ReaderSearchResultItem`;
- parsing selected locator JSON back into a Readium `Locator`;
- navigating the current `IosEpubReaderViewController` to the result;
- applying highlight decorations through the Readium navigator;
- clearing highlight decorations when search is cancelled.

## iOS storage and assets

iOS owns platform file locations and asset installation. It does not own the
shared schema or processing semantics attached to those files.

Current iOS-owned storage and asset wiring includes:

- book database creation through `IosBookLibraryStoreFactory`;
- `book.db` stored by SQLDelight native driver;
- cover files under Application Support `BookCovers`;
- debug lemma export files under Application Support `DebugLemmaIndex`;
- reader locator, last-readable-URI, and security bookmark values in
  `UserDefaults` suite `readium_reader`;
- global frequency database asset copying from bundle `frequency`;
- installed global frequency database under Application Support `frequency`;
- UDPipe model asset installation from bundle `udpipe`;
- installed UDPipe model under Application Support `udpipe-models`;
- native UDPipe execution through `IosTextAnalysisProvider`.

Use the storage, global-frequency, and native-runtime docs for schema,
generation, versioning, and ABI rules.

## iOS-specific parity notes

Current platform parity facts:

- iOS implements previous and next reader navigation through Readium Swift
  Toolkit.
- Android currently keeps previous and next reader actions as no-ops.
- iOS Swift uses security-scoped bookmarks for selected EPUB files.
- Android uses SAF persisted URI permissions.
- iOS debug lemma export writes per-book top-lemma files.
- Android has a debug folder batch processor; iOS does not currently expose the
  same batch folder UI.
- `app-ios-compose` exists in the repository, but this document covers the
  current SwiftUI product app in `app-ios-swift`.

## Change playbook

### iOS-only library UI affordance

For UI that only changes iOS presentation, update SwiftUI code and this
document if ownership changes. Do not update shared domain docs unless the
feature needs new `MainComponent` state or callbacks.

Examples:

- changing list row layout;
- changing file importer presentation;
- changing cover placeholder or loading behavior;
- adding an iOS-only visual treatment for processing status.

### File import and bookmark handling

For iOS import changes, decide which boundary owns the change:

- file importer UI, `UTType`, security-scoped bookmark storage, URL resolution,
  and security-scoped access belong here;
- `BookItem` fields, library callbacks, recent ordering, or processing status
  mapping belong to `library-domain.md`;
- preprocessing decisions belong to `book-preprocessing.md`;
- schema or transaction behavior belongs to `database-storage.md`.

### iOS reader chrome or overlay

If the feature is pure iOS presentation over the active reader, keep it in iOS
reader UI.

If it has shared visibility, async state, errors, results, or business rules,
add a reader subcomponent in the shared reader domain and render it from
SwiftUI.

If it requires Readium `Publication`, `EPUBNavigatorViewController`, locator
navigation, selection, decorations, or UIKit text APIs, add or extend an iOS
gateway behind the shared reader contract. Do not expose Readium or UIKit types
to `commonMain`.

### Readium runtime behavior

Changes to navigator hosting, locator collection, progression seeking,
previous/next navigation, center tap handling, search highlighting, security
access, or publication lifecycle belong here.

When these changes alter shared reader behavior, also update
`reader-search-domain.md`.

### iOS debug exports

Debug lemma exports are iOS-only. Keep output paths and export formatting in
iOS code.

If debug export changes require new preprocessing facts, update
`book-preprocessing.md`. If they only change iOS export shape or destination,
update this document.

### iOS storage, assets, or native wiring

iOS file paths, bundle resources, Application Support copy locations, and
platform repository/provider wiring belong here.

Schema changes belong to `database-storage.md`. UDPipe adapter, model version,
provider lifecycle, cinterop, and native build changes belong to
`native-udpipe-runtime.md`.

## Verification

Use `docs/agent-harness.md` for the full verification matrix.

Use the `Agent simulator runbook` in this document when an iOS-specific change
needs real build, install, and launch verification, such as SwiftUI root
rendering, file importer flow, Readium runtime, reader/search UI, security
bookmark restore, bundled asset installation, or lifecycle cleanup.

The runbook uses `xcodebuild` with deterministic derived data:

```bash
xcodebuild -project app-ios-swift/app-ios-swift.xcodeproj -scheme app-ios-swift -configuration Debug -destination 'platform=iOS Simulator,id=806717FC-839D-461B-B4A0-DAEE93A128B8' -derivedDataPath app-ios-swift/build build
```

Shared component behavior belongs to the shared domain tests, usually:

```bash
./gradlew :shared:test
```

No Swift test suite is assumed unless one is added later. Use simulator smoke
verification for iOS runtime behavior that depends on Readium, UIKit, or
security-scoped file access.

## Coverage expectations

No app tests are required for documentation-only changes to this file.

iOS coverage should be change-driven. Do not add simulator, native, or gateway
tests simply because this document exists.

When iOS platform behavior changes, add focused coverage where it controls
development risk:

- manual or smoke verification for file importer and security bookmark restore;
- smoke verification for real Readium import, rendering, previous/next
  navigation, search, and result navigation changes;
- lifecycle checks when changing locator persistence, background persistence,
  security-scoped access, search cancellation, or publication closing;
- focused checks for bundled global frequency database and UDPipe model
  installation when those assets or paths change;
- debug smoke coverage when changing debug lemma exports.

Shared component tests belong with the shared domain that owns the contract.
iOS tests should validate iOS adaptation, not duplicate shared state machine
tests.
