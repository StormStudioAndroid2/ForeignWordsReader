# Book preprocessing pipeline

This is the current preprocessing path for each EPUB in the local library.
It runs after import, and also on first open when the stored index is missing
or stale for the current analysis version and preprocessing pipeline fingerprint.

Universal preprocessing work belongs in the shared pipeline. Platform code may
extract source text or provide services behind shared contracts, but the stage
ordering, persistence decision, and durable outputs should stay in `shared`.

## 1. Library action starts preprocessing

The shared library component calls the platform `BookLibraryGateway`.

- Import path: `DefaultMainComponent.onEpubSelected(...)` calls `importBook(...)`.
- Existing book path: `DefaultMainComponent.onBookClicked(...)` calls
  `markBookOpened(...)`; the platform gateway starts preprocessing only if
  `BookLibraryStore.hasCurrentBookIndex(...)` is false.

Files to look:

- `shared/src/commonMain/kotlin/com/example/myapplication/shared/main/DefaultMainComponent.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/main/BookLibraryGateway.kt`
- `app-android/src/main/kotlin/com/example/myapplication/android/reader/AndroidBookLibraryGateway.kt`
- `app-ios-swift/app-ios-swift/reader-platform/IosBookLibraryGateway.swift`

## 2. EPUB is opened and saved as a library book

The platform gateway opens the EPUB with Readium, extracts metadata, creates a
stable book id from the EPUB URI, saves the cover image, and upserts the `book`
row. It also writes an initial `Processing` status in
`book_analysis_metadata` so the library can show that work has started.

Files to look:

- `app-android/src/main/kotlin/com/example/myapplication/android/reader/AndroidBookLibraryGateway.kt`
- `app-ios-swift/app-ios-swift/reader-platform/IosBookLibraryGateway.swift`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/data/BookLibraryStore.kt`
- `shared/src/commonMain/sqldelight/com/example/myapplication/shared/data/BookDatabase.sq`

## 3. Readium text is converted to sections

The platform layer extracts publication text and turns it into shared
`TextSection` objects.

- Android uses `publication.content()?.text()` and then strips HTML/whitespace
  in `String.toTextSections()`.
- iOS uses `publication.content()?.text(separator: "\n\n")` and splits the
  combined text into non-empty sections.

Files to look:

- `app-android/src/main/kotlin/com/example/myapplication/android/reader/AndroidBookLibraryGateway.kt`
- `app-ios-swift/app-ios-swift/reader-platform/IosBookLibraryGateway.swift`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/processing/BookProcessingModels.kt`

## 4. Shared processor validates the book

`BookAnalysisProcessor.processBook(...)` trims empty sections, fails early if
no readable text exists, detects language with `SimpleLanguageDetector`, and
currently accepts only English (`en`). It then checks the current
`BookPreprocessingPipeline.fingerprint`. If a completed index already exists
for the same language, provider, model version, index version, and pipeline
fingerprint, it returns the stored status unless `force = true`.

Files to look:

- `shared/src/commonMain/kotlin/com/example/myapplication/shared/processing/BookAnalysisProcessor.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/processing/BookPreprocessingPipeline.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/processing/LanguageDetector.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/processing/BookProcessingModels.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/data/BookLibraryStore.kt`

## 5. Shared preprocessing stages run

The default shared pipeline is:

1. `UdpipeAnalysisStage`
2. `BuildLemmaCandidatesStage`
3. `FilterLemmaCandidatesStage`
4. `ScoreLemmaIndexStage`
5. `PersistBookIndexStage`

Every stage has a stable lowercase `stageId` and positive `version`. The ordered
`stageId@version` values form the pipeline fingerprint stored in
`book_analysis_metadata.pipeline_fingerprint`. Add or version-bump a stage when
its durable output or semantics change enough that existing books must be
reprocessed.

Files to look:

- `shared/src/commonMain/kotlin/com/example/myapplication/shared/processing/BookPreprocessingPipeline.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/processing/BookAnalysisProcessor.kt`
- `shared/src/commonTest/kotlin/com/example/myapplication/shared/processing/BookPreprocessingPipelineTest.kt`
- `shared/src/commonTest/kotlin/com/example/myapplication/shared/processing/BookAnalysisProcessorTest.kt`

## 6. UDPipe model is installed and run

`UdpipeAnalysisStage` uses the platform analysis provider to make sure the
bundled English UDPipe model is available, load a native UDPipe engine, analyze
each `TextSection`, and return CoNLL-U text.

Files to look:

- `shared/src/androidMain/kotlin/com/example/myapplication/shared/processing/AndroidTextAnalysisProvider.kt`
- `shared/src/iosMain/kotlin/com/example/myapplication/shared/processing/IosTextAnalysisProvider.kt`
- `native/udpipe/adapter/udpipe_adapter.cpp`
- `native/udpipe/adapter/udpipe_jni.cpp`
- `shared/src/androidMain/assets/udpipe/english-ewt.udpipe`
- `app-ios-swift/app-ios-swift/Resources/udpipe/english-ewt.udpipe`

## 7. CoNLL-U is parsed into shared tokens

`ConlluParser` ignores comments, blank lines, multiword-token ranges, and empty
nodes. For regular token rows it keeps surface text, lemma, UPOS tag, token
order, section id, and token type (`Word`, `Punctuation`, `Symbol`, `Other`).

Files to look:

- `shared/src/commonMain/kotlin/com/example/myapplication/shared/processing/ConlluParser.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/processing/BookProcessingModels.kt`
- `shared/src/commonTest/kotlin/com/example/myapplication/shared/processing/ConlluParserTest.kt`

## 8. Lemma candidates are built, filtered, and scored

`BuildLemmaCandidatesStage` calls `BookIndexBuilder`, which filters to countable
word tokens, normalizes lemma keys, keeps chunk ids based on all countable
word-like tokens, and builds candidate statistics:

- Lemma totals and UPOS counts.
- Dominant UPOS and `PROPN` ratio per lemma.
- Chunk-level candidate counts with chunks of 800 word-like tokens.
- Bundled global Zipf frequency, when available.

`FilterLemmaCandidatesStage` rejects candidate lemmas before TF-IDF scoring when:

- Dominant UPOS is not `NOUN`, `VERB`, `ADJ`, or `ADV`.
- The lemma is a UDPipe contraction fragment: `ca`, `wo`, `n't`, `'s`, `'re`,
  `'ve`, `'ll`, `'d`, or `'m`.
- The lemma contains digits.
- The lemma length after apostrophe normalization is less than 3.
- `PROPN_count / total_count >= 0.4`.
- After apostrophe normalization and stripping apostrophes for validation, any
  remaining character is non-letter.
- Global Zipf frequency is missing and `total_count < 5`.

`ScoreLemmaIndexStage` computes the TF-IDF-like rarity score only for accepted
lemmas and builds the two persisted indexes:

- Book-level lemma totals in `book_lemma_total`.
- Chunk-level lemma counts in `book_chunk_lemma_count`, with chunks of 800
  word-like tokens, but only for accepted lemmas.

Lemmas are ranked by `tfIdfScore`, then `totalCount`, then alphabetically.

Files to look:

- `shared/src/commonMain/kotlin/com/example/myapplication/shared/processing/BookIndexBuilder.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/processing/TfIdfScoring.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/processing/GlobalFrequencyRepository.kt`
- `shared/src/androidMain/kotlin/com/example/myapplication/shared/processing/AndroidGlobalFrequencyRepository.kt`
- `shared/src/iosMain/kotlin/com/example/myapplication/shared/processing/IosGlobalFrequencyRepository.kt`
- `shared/src/commonTest/kotlin/com/example/myapplication/shared/processing/BookIndexBuilderTest.kt`

## 9. Results are persisted

On success, `PersistBookIndexStage` calls
`BookLibraryStore.replaceBookIndex(...)`. That transaction deletes old lemma
rows for the book/language, upserts a `Completed` processing status with the
pipeline fingerprint, then inserts fresh book-level and chunk-level lemma
counts. On failure, the processor writes a `Failed` status and stores the error
message.

Files to look:

- `shared/src/commonMain/kotlin/com/example/myapplication/shared/processing/BookAnalysisProcessor.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/data/BookLibraryStore.kt`
- `shared/src/commonMain/sqldelight/com/example/myapplication/shared/data/BookDatabase.sq`

## 10. Current consumers and debug output

The library UI currently shows processing state, token count, and failures from
`BookItem`. Debug builds also export top lemma files after processing. The saved
lemma index is available through `BookLibraryStore.getLemmaCounts(...)` and
`getChunkLemmaCounts(...)`; the reader does not recalculate frequencies.

Files to look:

- `shared/src/commonMain/kotlin/com/example/myapplication/shared/main/BookItem.kt`
- `app-android/src/main/kotlin/com/example/myapplication/android/ui/main/MainContent.kt`
- `app-ios-swift/app-ios-swift/MainView.swift`
- `app-android/src/main/kotlin/com/example/myapplication/android/reader/AndroidBookLibraryGateway.kt`
- `app-ios-swift/app-ios-swift/reader-platform/IosBookLibraryGateway.swift`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/data/BookLibraryStore.kt`

## Bundled global frequency database

The global frequency SQLite database is generated outside the app and bundled
into Android/iOS resources. The app copies it into local app storage and queries
it during index building.

Files to look:

- `scripts/generate-wordfreq-db.py`
- `shared/src/androidMain/assets/frequency/global-frequency.sqlite`
- `app-ios-swift/app-ios-swift/Resources/frequency/global-frequency.sqlite`

## Adding a new universal stage

For every new preprocessing stage:

- Design the tests before implementation. Include at least one stage-specific
  unit test, plus processor/persistence/failure coverage when the stage affects
  ordering, stored data, or errors.
- Add the stage in `shared` unless it depends on a platform service hidden
  behind a shared contract.
- Give it a stable lowercase `stageId` and positive `version`.
- Insert it in the default `BookPreprocessingPipeline` at the exact point where
  its inputs already exist.
- Extend `BookPreprocessingContext` with typed inputs/outputs instead of using
  ad hoc maps or string keys.
- Persist durable output through `BookLibraryStore` and SQLDelight migrations.
- Update this document and run the relevant shared tests.
