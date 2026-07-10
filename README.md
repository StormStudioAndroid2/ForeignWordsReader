# ForeignWordsReader

ForeignWordsReader is a Kotlin Multiplatform reading app focused on local EPUB
reading and per-book word analysis.

The app currently provides:

- a local book library;
- EPUB import/open flows;
- reader position restore;
- Android and iOS reader runtimes backed by Readium;
- per-book preprocessing with UDPipe;
- persisted lemma and chunk indexes;
- bundled global frequency data for scoring;
- platform-specific Android Compose and iOS SwiftUI product apps.

The project is no longer a plain Decompose template. Decompose is still the
shared component/navigation foundation, but product behavior is documented in
the domain docs below.

## Tech stack

- Kotlin Multiplatform for shared contracts, components, storage, and
  preprocessing.
- Decompose for shared root navigation and feature components.
- SQLDelight for local book and analysis storage.
- Readium Kotlin for Android EPUB import/rendering.
- Readium Swift Toolkit for iOS EPUB import/rendering.
- UDPipe through a small native C adapter for text analysis.
- Android Compose for the Android product app.
- SwiftUI plus UIKit wrappers for the iOS Swift product app.

## Project structure

- `shared`: shared models, Decompose components, SQLDelight stores,
  preprocessing, native runtime bindings, and platform implementations under
  `androidMain` and `iosMain`.
- `compose-ui`: shared Compose UI module used by non-Swift targets.
- `app-android`: Android product app, Android Compose screens, Readium Kotlin
  runtime, Android library gateway, and Android device runbook.
- `app-ios-swift`: current iOS SwiftUI product app, Readium Swift runtime,
  Swift platform gateways, bundled iOS resources, and simulator runbook.
- `app-ios-compose`: secondary/template iOS Compose project; not the current
  product documentation focus.
- `app-desktop`: desktop target retained from the multiplatform template.
- `native/udpipe`: UDPipe adapter, JNI bridge, cinterop header, and native
  build scripts.
- `scripts`: project support scripts, including global frequency DB generation.
- `docs`: domain documentation, agent routing, build notes, and incident notes.

## Documentation map

Start here when changing the project:

- Agent workflow and routing: `docs/agent-harness.md`
- Root/navigation architecture: `docs/domains/app-architecture.md`
- Library domain: `docs/domains/library-domain.md`
- Reader and search domain: `docs/domains/reader-search-domain.md`
- Book preprocessing: `docs/domains/book-preprocessing.md`
- Database and storage: `docs/domains/database-storage.md`
- Native UDPipe runtime: `docs/domains/native-udpipe-runtime.md`
- Android product app and device runbook:
  `docs/domains/android-product-app.md`
- iOS Swift product app and simulator runbook:
  `docs/domains/ios-swift-product-app.md`
- Global frequency DB build inputs: `docs/global-frequency-db.md`
- Old preprocessing compatibility pointer:
  `docs/book-preprocessing-pipeline.md`

## AI agent workflow

AI agents should not start from file names alone. Before code changes:

1. Read `docs/agent-harness.md`.
2. Use the task rank matrix and domain verification matrix in the harness.
3. Identify the domain areas touched by the requested change.
4. Read the relevant domain docs.
5. Check that the planned change fits the domain responsibility and does not
   cross an ownership boundary.
6. Implement inside the owning boundary.
7. Re-check adjacent domains.
8. Add focused tests where the changed contract needs protection.
9. Update docs when responsibility, flow, persistence, platform behavior, or
   test expectations change.
10. Run the smallest relevant verification set and report skipped checks.

`AGENTS.md` is the short mandatory entrypoint for agent behavior. The domain
docs remain the source of truth for ownership and change rules.

## Running and verification

Use targeted verification for the domain you changed.

The canonical task rank and domain verification matrix lives in
`docs/agent-harness.md`.

Shared/common behavior:

```bash
./gradlew :shared:test
```

Android:

- Build/install/launch commands are documented in
  `docs/domains/android-product-app.md`.
- The default agent path is explicit `assembleDebug` plus `adb install -r`.

iOS Swift:

- Simulator build/install/launch commands are documented in
  `docs/domains/ios-swift-product-app.md`.
- The default agent path uses `xcodebuild`, `simctl`, and deterministic
  `-derivedDataPath app-ios-swift/build`.

Docs-only changes:

```bash
LC_ALL=C grep -n '[^ -~]' README.md AGENTS.md docs/agent-harness.md
rg -n "agent-harness|Documentation map|Identify touched domains" README.md AGENTS.md docs
```

Do not run broad platform builds by default for documentation-only changes.

## Development notes

- Shared code owns contracts, state machines, storage semantics, and
  preprocessing semantics.
- Platform code owns Readium runtime details, UI frameworks, platform
  lifecycle, file permissions/bookmarks, and platform-specific launch flows.
- Do not expose Android, iOS, Readium, UIKit, SwiftUI, Fragment, native handle,
  or file-system details into `commonMain`.
- When a change crosses a boundary, update the owning domain docs and the
  affected tests together.
