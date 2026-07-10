# App architecture

This document describes the root application architecture: what the root
component owns, how the current Decompose navigation stack is wired, and how to
extend navigation without leaking platform or feature-specific responsibilities
into the app shell.

## Responsibility

The root architecture owns app-level navigation and feature composition.

It is responsible for:

- creating the Decompose root component;
- owning the single app-level `ChildStack`;
- deciding the initial stack at app startup;
- wiring feature components to platform gateways and factories;
- translating app-level back requests into stack operations;
- keeping feature components independent from platform UI and Readium runtime
  details.

The main source files are:

- `shared/src/commonMain/kotlin/com/example/myapplication/shared/root/RootComponent.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/root/DefaultRootComponent.kt`
- `app-android/src/main/kotlin/com/example/myapplication/android/MainActivity.kt`
- `app-ios-swift/app-ios-swift/iOSApp.swift`

## Out of scope

The root architecture does not own:

- Readium rendering or publication lifecycle;
- Android Compose screen internals;
- SwiftUI screen internals;
- SQLDelight schema or local storage rules;
- EPUB import implementation details;
- NLP preprocessing, frequency indexes, or global frequency database logic.

Those responsibilities belong to their feature, platform, storage, or
preprocessing domains.

## Current navigation model

The app currently uses one Decompose `ChildStack`.

```text
RootComponent
`-- ChildStack
    |-- Main
    |-- Reader(uriString)
    `-- Welcome
```

The public root model is `RootComponent.Child`:

- `Main` wraps `MainComponent`;
- `Reader` wraps `ReaderComponent`;
- `Welcome` wraps `WelcomeComponent`.

The private serializable navigation model is `DefaultRootComponent.Config`:

- `Config.Main`;
- `Config.Reader(uriString)`;
- `Config.Welcome`.

`Config` is private to `DefaultRootComponent`. External callers should use the
public component APIs and callbacks instead of constructing navigation configs
directly.

## Startup behavior

The initial stack depends on `initialReaderUriString`.

- When `initialReaderUriString` is `null`, the app starts with `Main`.
- When `initialReaderUriString` is present, the app starts with
  `[Main, Reader(uriString)]`.

This preserves the library as the base screen while restoring the last readable
EPUB on top of it.

Platform entrypoints provide the initial reader URI:

- Android passes `lastReadableEpubUriString(this)`;
- iOS Swift passes `IosReaderPersistence.lastReadableEpubUriString()`.

## Feature wiring

`DefaultRootComponent` wires feature components by passing callbacks and
platform abstractions into feature constructors.

`MainComponent` is created with:

- `BookLibraryGateway`, provided by the platform entrypoint;
- `onShowWelcome`, which pushes `Config.Welcome`;
- `onOpenReader`, which pushes `Config.Reader(uriString)`.

`ReaderComponent` is created through `ReaderComponentFactory`. This keeps the
shared root independent from Android Readium, iOS Readium, and platform reader
UI implementation details.

`WelcomeComponent` is created directly by the root. It is a template/legacy
route and should not be treated as the recommended example for new product
features. New product routes should be documented in the relevant domain before
being added to the root graph.

## Back behavior

Back behavior is split by ownership.

- Reader and welcome components receive an `onFinished` callback that calls
  `navigation.pop()`.
- Root UI can call `RootComponent.onBackClicked(toIndex)`, which delegates to
  `navigation.popTo(index = toIndex)`.
- Decompose handles platform back button integration through
  `handleBackButton = true`.

Feature components should request navigation through callbacks provided by the
root. They should not own or mutate the root `StackNavigation` directly.

## Platform entrypoints

Android creates the root in `MainActivity`.

It provides:

- `AndroidBookLibraryGateway`;
- `DefaultAndroidReaderComponent` through `ReaderComponentFactory`;
- `lastReadableEpubUriString(this)` as the startup reader restore value;
- Android-only root rendering with `AndroidRootContent`.

Android platform details are documented in
`docs/domains/android-product-app.md`.

iOS Swift creates the root in `AppDelegate`.

It provides:

- `IosBookLibraryGateway`;
- `IosReaderComponentFactory`;
- `IosReaderPersistence.lastReadableEpubUriString()` as the startup reader
  restore value;
- SwiftUI root rendering with `RootView`.

iOS Swift platform details are documented in
`docs/domains/ios-swift-product-app.md`.

The shared root owns navigation. Platform entrypoints own platform services and
rendering.

## Extension playbook

Use the smallest navigation primitive that matches the user experience.

If a screen changes the main user journey and should participate in app-level
back navigation or state restoration, add it to the root navigation model. If a
surface is temporary and belongs to an active feature, keep it inside that
feature component or platform UI.

### Screen inside the navigation graph

Use a root graph screen for full-screen flows such as settings, book details,
import results, dictionary, vocabulary list, or library filters.

To add one:

1. Add a new public `RootComponent.Child` type.
2. Add a matching private serializable `DefaultRootComponent.Config`.
3. Add a component interface and implementation in the owning feature domain.
4. Add a factory or gateway only when platform services are required.
5. Add a branch in `DefaultRootComponent.child(...)`.
6. Add Android and iOS root rendering branches.
7. Add tests for push, payload preservation, back/pop behavior, and platform
   rendering coverage.

Do not reuse `Welcome` as a product pattern. It is a legacy/template route.

### Bottom sheet

Use a bottom sheet for short-lived choices or controls that should not become a
primary screen, such as sort options, filter pickers, language selection, reader
quick settings, or import source selection.

Feature-local sheets should stay inside the feature component. For example, a
library filter sheet belongs in the library/main domain, and reader quick
settings belong in the reader domain.

Global sheets should be modeled with a separate root-level slot-like state, not
by overloading the main `ChildStack`. A global sheet must dismiss before the
underlying stack is popped.

When adding a bottom sheet, include tests for:

- opening the sheet;
- dismissing the sheet;
- back dismissing the sheet before popping the stack;
- preserving the active child underneath the sheet.

### Modal screen

Use a modal for blocking or decision-heavy UI that appears above the current
screen, such as destructive confirmation, permission explanation, import
progress, or an unrecoverable error.

If the modal is shared, app-level, and should be restorable, model it as a
separate root-level modal slot. If the modal is platform-only, keep it in the
platform UI and expose only the result callback to shared code.

A modal should not replace the active `ChildStack` child when dismissing it is
expected to return to exactly the same screen state.

When adding a modal, include tests for:

- opening the modal over the current child;
- dismissing the modal and restoring the same active child;
- confirming the modal action and emitting the expected navigation or feature
  event.

### Screen or overlay above the reader

Reader-scoped overlays belong to the reader domain when they depend on the
active publication, navigator, locator, search session, reader progress, or
reader chrome.

Examples:

- search overlay;
- table of contents;
- reader settings;
- bookmarks;
- vocabulary panel;
- dictionary popup.

These should be implemented through `ReaderComponent`, reader subcomponents, or
the reader platform runtime. They should not be added to `RootComponent` unless
they become app-level screens that can exist without an active reader.

The current search overlay and reader chrome are reader-scoped examples.

When adding a reader overlay, include tests for:

- visibility state;
- dismiss behavior;
- action callbacks such as navigation to a locator;
- preserving reader progress and active reader state.

## Verification

Use `docs/agent-harness.md` for the full verification matrix.

For root navigation or shared root wiring changes, the default command is:

```bash
./gradlew :shared:test
```

`DefaultRootComponentTest` is the expected coverage home for initial stack,
reader push, welcome legacy routing, reader back, and indexed `popTo` behavior.
If that test does not exist yet, add focused coverage when changing those
contracts.

Run Android and iOS platform checks only when a change adds or modifies root
rendering branches, platform entrypoint dependencies, or startup restore
wiring.

## Test contract

The current root architecture should be covered by common unit tests for:

- starting with `Main` when no initial reader URI exists;
- starting with `[Main, Reader(uriString)]` when an initial reader URI exists;
- opening reader from main when a book is clicked;
- opening reader after EPUB import succeeds;
- opening and closing the legacy `Welcome` route;
- popping reader back to main;
- delegating indexed back navigation through `popTo`.

Every new navigation primitive must ship with tests in the same change.

For a new graph screen, test push, serialized payload, back/pop behavior, and
platform rendering branches.

For a bottom sheet, test open, dismiss, back-dismiss-before-pop, and preservation
of the active child underneath.

For a modal, test open over current child, dismiss back to the same child, and
the modal action result.

For a reader overlay, test visibility, dismiss, action callbacks, and reader
state preservation.
