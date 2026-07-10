# Agent harness

This document is the routing guide for AI agents working in ForeignWordsReader.
It explains how to choose the right domain docs, how to keep changes inside the
owning boundary, when to look at adjacent domains, and how to pick focused
verification.

The goal is predictable agent behavior. The harness does not replace the domain
docs; it tells agents which domain docs to read and how to apply them.

## Workflow checklist

Use this checklist before and after every non-trivial change.

1. Identify touched domains before editing.
2. Read each domain doc's `Responsibility`, `Out of scope`, `Change playbook`,
   `Test contract`, or `Coverage expectations`.
3. Check whether the planned change fits the domain purpose and preserves the
   domain's core logic.
4. Define the feature architecture according to the owning domain docs.
5. Implement the change inside the owning boundary.
6. Re-check adjacent domains that may be affected by the implementation.
7. Read domain-specific test expectations before adding or changing tests.
8. Add or update tests only where they protect the changed contract.
9. Update docs when ownership, flow, persistence, platform behavior, or test
   contract changes.
10. Run the smallest relevant verification set and report what was skipped.

If a requested change touches more than one domain, read all owning domain docs
before editing. If the domain ownership is unclear, inspect the current code and
choose the document whose `Responsibility` section matches the behavior being
changed.

## Task rank matrix

Use task rank to choose how much documentation, testing, and platform
verification is necessary.

| Rank | Typical change | Required routing | Verification default |
| --- | --- | --- | --- |
| R0 docs-only | README, agent docs, domain text | This harness plus affected docs | ASCII check, reference `rg`, `git diff --check` |
| R1 local logic change | One shared function, parser, mapper, or store helper | Owning domain doc | Focused unit tests for the changed contract |
| R2 new feature in one domain | New shared state, action, screen, overlay, or store behavior | Owning domain doc plus adjacent-domain rules | Owning-domain tests plus `./gradlew :shared:test` when shared code changes |
| R3 cross-domain or platform change | Shared contract plus Android/iOS runtime, UI, or lifecycle | All owning and adjacent domain docs | Shared tests plus affected platform build or runbook |
| R4 storage, native, or preprocessing risk | SQL migration, generated asset, pipeline fingerprint, UDPipe ABI, model, or index semantics | Storage, preprocessing, native, and global-frequency docs as applicable | Versioning checks, focused tests, native/build checks, and targeted smoke verification |

Prefer the lowest rank that honestly covers the change. Escalate the rank when
the implementation crosses a boundary discovered during coding.

## Routing table

| Touched path or behavior | Read first | Also check |
| --- | --- | --- |
| `shared/src/commonMain/.../root` | `docs/domains/app-architecture.md` | Android and iOS platform docs if rendering branches change |
| `shared/src/commonMain/.../main` | `docs/domains/library-domain.md` | Preprocessing and storage if import/open behavior changes |
| `shared/src/commonMain/.../reader` | `docs/domains/reader-search-domain.md` | Android and iOS platform docs for runtime/UI branches |
| `shared/src/commonMain/.../processing` | `docs/domains/book-preprocessing.md` | Native UDPipe and storage if model/provider/schema behavior changes |
| `shared/src/commonMain/sqldelight` | `docs/domains/database-storage.md` | Library, preprocessing, Android, and iOS install paths |
| `shared/src/androidMain` | `docs/domains/android-product-app.md` | Owning shared domain if a shared contract changes |
| `app-android` | `docs/domains/android-product-app.md` | Root/library/reader/preprocessing docs when callbacks or contracts change |
| `shared/src/iosMain` | `docs/domains/ios-swift-product-app.md` | Owning shared domain if a shared contract changes |
| `app-ios-swift` | `docs/domains/ios-swift-product-app.md` | Root/library/reader/preprocessing docs when callbacks or contracts change |
| `native/udpipe` | `docs/domains/native-udpipe-runtime.md` | Book preprocessing if output semantics change |
| `scripts` frequency tools | `docs/global-frequency-db.md` | Database storage for bundled asset versioning |
| Bundled frequency DB assets | `docs/domains/database-storage.md` | `docs/global-frequency-db.md` |
| `README.md` or `AGENTS.md` | This file | Affected domain docs |

## Domain summaries

Use these summaries only for routing. The linked docs are authoritative.

- Root architecture owns app-level Decompose navigation and feature wiring.
- Library owns the local book list, import/open commands, recent ordering, and
  processing status display contract.
- Reader/search owns active-book reader state, locator callbacks, reader
  actions, search state, and reader-scoped feature contracts.
- Book preprocessing owns per-book analysis from extracted text sections to
  persisted lemma and chunk indexes.
- Database storage owns SQLDelight schema, migrations, runtime DB install, and
  bundled frequency DB runtime copy/versioning.
- Native UDPipe runtime owns model asset install, native engine lifecycle, C
  adapter ABI, Android JNI, and iOS cinterop.
- Android product app owns Android entrypoint, Compose rendering, SAF,
  Readium Kotlin, Android runtime wiring, and Android device runbook.
- iOS Swift product app owns SwiftUI entrypoint, file importer, security
  bookmarks, Readium Swift Toolkit, iOS runtime wiring, and simulator runbook.

## Adjacent-domain rules

Re-check adjacent domains after the implementation shape is clear.

- Navigation changes may affect Android and iOS rendering branches.
- Library import/open changes may affect preprocessing and storage.
- Reader feature changes may affect Android and iOS platform runtime.
- Preprocessing changes may affect database schema, native UDPipe, and global
  frequency behavior.
- Storage changes may affect Android and iOS asset install paths.
- Native UDPipe changes may affect preprocessing output semantics and index
  versioning.
- Platform changes should not rewrite shared contracts unless the owning shared
  domain doc is updated.
- Documentation changes should update links or routing tables when a new domain
  or operational runbook becomes canonical.

## Architecture checklist for new features

Before implementing a new feature, classify it:

- App-level full screen: root architecture.
- Library state or import/open behavior: library domain.
- Active-book overlay or reader state: reader/search domain.
- Readium/native reader capability: reader/search plus platform app docs.
- EPUB text analysis or per-book index behavior: book preprocessing.
- SQL schema or persisted query behavior: database storage.
- UDPipe model, native adapter, or provider lifecycle: native UDPipe runtime.
- Android-only UI/runtime behavior: Android product app.
- iOS-only UI/runtime behavior: iOS Swift product app.

Then decide:

- Which domain owns the public contract?
- Which platform owns the concrete runtime behavior?
- Which adjacent domains need rendering, persistence, or docs updates?
- Which existing test contract or coverage expectation applies?
- Which verification command is the smallest useful check?

## Testing rules

Tests are not added for their own sake. Add or update tests when they protect a
changed contract, state transition, persistence rule, parser/scoring behavior,
or platform adapter behavior that can be isolated.

Use domain docs for specific expectations:

- `Test contract` means the domain expects concrete coverage for shared
  behavior.
- `Coverage expectations` means platform/native checks should be change-driven
  and may be smoke/manual when real runtime dependencies are required.

Do not duplicate shared state-machine tests in Android or iOS UI tests. Platform
tests should validate platform adaptation, lifecycle, and mapping behavior.

## Current coverage status

Current `shared/src/commonTest` coverage is strongest around preprocessing:

- `BookPreprocessingPipelineTest`;
- `BookAnalysisProcessorTest`;
- `ConlluParserTest`;
- `BookIndexBuilderTest`;
- `LemmaCandidateFilterTest`.

Root, library component, search component, store, and migration tests are
documented as expected contracts in the domain docs, but they are not all
present yet. When changing those areas, add tests only where they protect the
contract being changed.

## Verification guide

Pick the smallest useful verification set.

Shared/common behavior:

```bash
./gradlew :shared:test
```

Android build/run:

- Use `docs/domains/android-product-app.md`.
- Prefer the documented `assembleDebug` plus `adb install -r` flow.
- Do not run `adb uninstall` unless the user asks for a clean state.

iOS simulator build/run:

- Use `docs/domains/ios-swift-product-app.md`.
- Prefer the documented `xcodebuild` plus `simctl install` flow.
- Do not run `xcrun simctl erase` unless the user asks for a clean state.

Preprocessing-stage work:

- Use skill `add-book-preprocessing-stage`.
- Read `docs/domains/book-preprocessing.md`.
- Keep fingerprint/versioning and tests aligned with the domain doc.

Docs-only work:

```bash
LC_ALL=C grep -n '[^ -~]' README.md AGENTS.md docs/agent-harness.md
rg -n "agent-harness|Documentation map|Identify touched domains|add-book-preprocessing-stage|android-product-app|ios-swift-product-app" README.md AGENTS.md docs
```

If a check cannot be run, report it explicitly with the reason.

## Domain verification matrix

Use this matrix after selecting the owning domain.

| Domain | Usual command | Current or expected coverage | Platform verification trigger |
| --- | --- | --- | --- |
| App architecture | `./gradlew :shared:test` | Future `DefaultRootComponentTest` for initial stack, push, pop, and `popTo` | Run Android/iOS rendering checks when adding root children |
| Library | `./gradlew :shared:test` | Future `DefaultMainComponentTest` and `BookLibraryStoreTest`; gateway helpers when extractable | Platform smoke when Readium import, metadata, cover, or text extraction changes |
| Reader/search | `./gradlew :shared:test` | Future `DefaultSearchComponentTest` and reader feature component tests | Android/iOS runbooks when reader runtime, overlay rendering, search, or navigation changes |
| Book preprocessing | `./gradlew :shared:test` | Existing processor, pipeline, parser, index builder, and filter tests | Native or platform smoke only when provider/model/runtime behavior changes |
| Database/storage | `./gradlew :shared:test` | Store and migration tests expected for schema/store changes | Platform asset-copy checks when install paths or bundled DB versions change |
| Native UDPipe runtime | `./gradlew :shared:buildUdpipeIos`; `./gradlew :shared:linkIosSimulatorArm64`; Android native build through `./gradlew :app-android:assembleDebug` | Common tests do not prove native runtime behavior | Smoke on affected platform when ABI, model install, provider lifecycle, or output semantics changes |
| Android product app | `./gradlew :app-android:assembleDebug` | Android unit or instrumentation tests only when useful for changed platform behavior | Use Android device runbook after Android runtime/UI changes |
| iOS Swift product app | Use the documented `xcodebuild` simulator runbook | No Swift test suite is assumed unless added later | Use simulator runbook after iOS runtime/UI changes |
| Global frequency DB | `python3 -m unittest scripts/test_generate_wordfreq_db.py` | Builder tests cover generation rules; use small generation smoke for output checks | Platform asset-copy checks when a new DB asset is bundled |

For global-frequency output smoke, use:

```bash
python3 scripts/generate-wordfreq-db.py --limit 10000 --output /tmp/global-frequency.sqlite
```

## Documentation update rules

Update documentation when a change affects:

- domain responsibility or ownership;
- app, feature, import, reader, preprocessing, storage, or native runtime flow;
- persisted schema, asset install path, model version, or pipeline fingerprint;
- Android or iOS platform behavior;
- device/simulator runbook commands;
- test contract or coverage expectation;
- agent routing.

Do not duplicate details across docs. Add a short pointer to the owning domain
doc instead.
