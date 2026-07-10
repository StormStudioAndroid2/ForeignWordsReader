# Library domain

This document describes the local book library domain. In code this area is
currently named `main`, but in product terms it is the library: the screen and
shared component that list local books, start EPUB import, open an existing
book, and surface preprocessing status.

## Responsibility

The library domain is responsible for:

- loading the local list of books;
- showing recent books in last-opened order;
- accepting a selected EPUB URI from platform UI;
- opening an existing book from the list;
- exposing book metadata, cover URI, and preprocessing status to UI;
- telling the root architecture when a reader screen should open;
- keeping shared library state independent from Readium and platform picker
  details.

The main source files are:

- `shared/src/commonMain/kotlin/com/example/myapplication/shared/main/MainComponent.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/main/DefaultMainComponent.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/main/BookLibraryGateway.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/main/BookItem.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/data/BookLibraryStore.kt`
- `shared/src/commonMain/sqldelight/com/example/myapplication/shared/data/BookDatabase.sq`

## Naming note

The current package and component names use `main` because the app started from
a template. Domain documents should call this area the library domain. Do not
rename packages, classes, or Gradle modules just to match this document.

When adding new library features, prefer product names in documentation and UI
copy, while preserving existing code names until a deliberate rename is planned.

## Out of scope

The library domain does not own:

- Readium rendering or reader navigation internals;
- Android or iOS file picker UI;
- Android Storage Access Framework details;
- iOS security-scoped bookmark details;
- NLP pipeline stage ordering or scoring semantics;
- global frequency database generation;
- platform debug batch UI behavior.

Those responsibilities belong to platform, reader, preprocessing, or tooling
domains.

## Shared component model

`MainComponent` exposes a single `Value<Model>`.

`MainComponent.Model` contains:

- `books`: the current recent-book list;
- `isLoading`: whether the library is loading or importing;
- `errorMessage`: the last user-visible library error, if any.

`DefaultMainComponent` loads books during initialization. It calls the platform
`BookLibraryGateway`, updates the model from gateway callbacks, and calls the
root-provided reader callback when a book should open.

User actions:

- `onBookClicked(uriString)` marks an existing book as opened, moves it to the
  top of the list, clears any previous error, and opens reader for the same URI.
- `onEpubSelected(uriString)` starts import, sets loading, opens reader after
  the first imported `BookItem` result, and later accepts preprocessing status
  updates through `onProcessingChanged`.
- `onShowWelcomeClicked()` is a legacy/template action. It should not be used
  as a pattern for new library product features.

When a book changes, the component inserts that book at the top of the list and
removes any older item with the same `uriString`.

## Gateway contract

`BookLibraryGateway` is the shared boundary between the library component and
platform EPUB services.

The gateway has three operations:

- `loadBooks(onResult, onError)` returns the recent local library list.
- `importBook(uriString, onResult, onProcessingChanged, onError)` imports or
  refreshes a selected EPUB and reports later processing changes.
- `markBookOpened(uriString, onResult, onError)` updates recency for an existing
  book and may trigger platform-side preprocessing when analysis is missing or
  stale.

All callbacks must be treated as asynchronous. A platform implementation may
call `onResult` first and call `onProcessingChanged` later after background
preprocessing finishes. The shared component must remain valid if callbacks
arrive after the reader has already opened.

Gateway implementations own platform work such as:

- resolving and persisting file access;
- opening EPUBs with Readium;
- extracting title, author, cover, and text sections;
- saving cover images;
- creating stable book ids;
- starting preprocessing through shared processing contracts.

`EmptyBookLibraryGateway` is only a fallback for platforms that do not support
the EPUB library.

## Store contract

`BookLibraryStore` is the shared SQLDelight-backed library store. It owns the
durable representation of books, processing metadata, and persisted lemma
indexes.

The SQL schema currently stores:

- `book`: stable book id, URI, title, author, cover URI, and last-opened time;
- `book_analysis_metadata`: per-book analysis status for a language;
- `book_lemma_total`: persisted book-level lemma totals and scores;
- `book_chunk_lemma_count`: persisted per-chunk lemma counts.

Library queries:

- `getRecentBooks(language)` returns books ordered by
  `last_opened_at_millis DESC`.
- `getBook(uriString, language)` returns a single book joined with processing
  metadata for that language.
- `upsertBook(book)` inserts or replaces book metadata.
- `markBookOpened(uriString, lastOpenedAtMillis)` updates recency and returns
  the updated book.

Processing queries:

- `getProcessingStatus(bookId, language)` returns the stored analysis status.
- `hasCurrentBookIndex(...)` checks completed analysis by language, provider,
  model version, index version, and pipeline fingerprint.
- `upsertProcessingStatus(status)` writes processing state.
- `replaceBookIndex(status, index)` atomically replaces lemma totals and chunk
  counts for the book/language and stores the completed status.

`BookItem` is the UI-facing projection of `book` joined with
`book_analysis_metadata`. Missing analysis metadata maps to
`BookProcessingState.NotStarted`.

`docs/domains/database-storage.md` is the source of truth for database storage,
schema migration, and versioning rules around `book.db`.

## Import and open flow

The import flow is:

1. Platform UI obtains an EPUB URI.
2. `DefaultMainComponent.onEpubSelected(uriString)` calls
   `BookLibraryGateway.importBook(...)`.
3. The platform gateway resolves file access, opens the EPUB with Readium,
   extracts metadata and cover, creates a stable book id, and upserts `book`.
4. The platform gateway writes an initial `Processing` status.
5. `onResult(book)` updates shared library state and opens reader.
6. The platform gateway starts preprocessing in the background.
7. `onProcessingChanged(book)` updates the visible book row when processing
   completes or fails.

The existing-book flow is:

1. `DefaultMainComponent.onBookClicked(uriString)` calls
   `BookLibraryGateway.markBookOpened(...)`.
2. The platform gateway updates `last_opened_at_millis`.
3. `onResult(book)` moves the book to the top of the list and opens reader.
4. The platform gateway may start preprocessing when the current book index is
   missing or stale.

The library opens reader through root callbacks only. It does not create or own
reader components.

## Boundary with preprocessing

The library domain starts and observes book preprocessing, but it does not own
preprocessing semantics.

Library-owned concerns:

- whether a book row displays `NotStarted`, `Processing`, `Completed`, or
  `Failed`;
- whether token counts and processing errors are visible in `BookItem`;
- when platform import/open paths ask preprocessing to run.

Preprocessing-owned concerns:

- language detection;
- UDPipe stage execution;
- lemma filtering and scoring;
- pipeline fingerprint rules;
- durable index contents;
- reprocessing semantics when analysis versions change.

`docs/domains/book-preprocessing.md` is the source of truth for preprocessing
behavior.

## Platform responsibilities

Android and iOS gateways implement the shared `BookLibraryGateway` contract.

Android owns:

- Storage Access Framework URI permissions;
- Readium Kotlin publication opening;
- Android cover saving;
- Android text section extraction;
- Android debug folder processing entrypoint;
- debug lemma export files.

iOS Swift owns:

- security-scoped file access and bookmark persistence;
- Readium Swift Toolkit publication opening;
- iOS cover saving;
- iOS text section extraction;
- iOS background preprocessing dispatch;
- debug lemma export files.

Both platforms must return `BookItem` values that are compatible with
`BookLibraryStore` and the shared component model.

## Debug batch note

Android debug folder processing is a platform debugging tool. It is useful for
bulk-importing EPUB files and exporting processing logs, but it is not part of
the shared library contract.

Shared library code should not depend on the debug batch API. Future details for
that surface should live in the Android product app domain document.

## Verification

Use `docs/agent-harness.md` for the full verification matrix.

For shared library component, model, gateway contract, or store mapping changes,
the default command is:

```bash
./gradlew :shared:test
```

`DefaultMainComponentTest` and `BookLibraryStoreTest` are the expected coverage
homes for shared library state and storage behavior. If they do not exist yet,
add focused coverage when changing the affected contract.

Run Android or iOS smoke checks only when the change touches platform EPUB
import, Readium metadata extraction, cover saving, text section extraction, or
platform gateway lifecycle. Do not duplicate shared component tests in platform
UI tests.

## Test contract

The shared component should be covered by `DefaultMainComponentTest` cases for:

- initial load success and error;
- import setting loading state before the gateway returns;
- successful import adding the book and opening reader;
- `onProcessingChanged` replacing an existing book row;
- clicking an existing book calling `markBookOpened` and opening reader;
- gateway errors updating `errorMessage`;
- book updates moving the affected book to the top without duplicates.

The store should be covered by `BookLibraryStoreTest` cases for:

- `upsertBook` and `getBook` by URI;
- recent ordering by `last_opened_at_millis`;
- `markBookOpened` updating recency;
- missing processing metadata mapping to `NotStarted`;
- stored processing states mapping to `BookItem`;
- `hasCurrentBookIndex` matching the current analysis contract;
- `replaceBookIndex` replacing old lemma totals and chunk counts atomically;
- lemma totals ordered by score, count, and lemma;
- chunk lemma counts ordered by local count and lemma.

Platform gateway tests should cover helper-level behavior when it is extractable
without Readium integration:

- fallback title generation;
- stable id generation;
- cover path preservation;
- text section splitting;
- stale or missing processing trigger decisions.

Real EPUB import through Readium and Android folder batch processing can remain
platform smoke or integration tests. They are not required for the first
unit-only pass.

Every new field shown through `BookItem` must be backed by store mapping tests
and at least one shared component model test.
