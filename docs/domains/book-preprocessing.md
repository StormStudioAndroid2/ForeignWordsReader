# Book preprocessing domain

This document describes the book preprocessing domain: what turns extracted
book text into durable per-book lemma indexes, which contracts own processing
state and freshness, where platform text extraction stops, and how future
preprocessing stages should be added safely.

## Responsibility

The book preprocessing domain owns per-book analysis after a platform has
extracted readable text from an EPUB.

It is responsible for:

- validating extracted `TextSection` input;
- detecting the analysis language;
- deciding whether a current completed index can be reused;
- running the ordered shared preprocessing pipeline;
- owning stage ordering, stage versions, and pipeline fingerprint semantics;
- producing durable book-level lemma totals;
- producing durable chunk-level lemma counts;
- writing `Processing`, `Completed`, and `Failed` analysis statuses;
- keeping preprocessing semantics in shared code instead of Android or iOS
  gateways.

The main source files are:

- `shared/src/commonMain/kotlin/com/example/myapplication/shared/processing/BookAnalysisProcessor.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/processing/BookPreprocessingPipeline.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/processing/BookProcessingModels.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/processing/BookIndexBuilder.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/processing/ConlluParser.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/processing/TfIdfScoring.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/data/BookLibraryStore.kt`
- `shared/src/commonMain/sqldelight/com/example/myapplication/shared/data/BookDatabase.sq`

## Out of scope

The book preprocessing domain does not own:

- EPUB picker UI or import UI;
- Readium rendering;
- Android Storage Access Framework permissions;
- iOS security-scoped file access;
- native UDPipe binary implementation details;
- global frequency database generation;
- reader UI presentation;
- app-level navigation.

Those responsibilities belong to the library, platform app, native runtime,
global frequency database, reader, or root architecture domains.

## Current flow

Preprocessing starts from library import or open flows, but the library domain
does not own preprocessing semantics.

The current flow is:

1. `DefaultMainComponent` calls the platform `BookLibraryGateway`.
2. The platform gateway opens the EPUB with Readium.
3. The platform gateway extracts metadata, creates a stable book id, saves the
   cover when available, and upserts the `book` row.
4. The platform gateway extracts publication text and converts it into shared
   `TextSection` values.
5. The platform gateway starts `BookAnalysisProcessor.processBook(...)`.
6. `BookAnalysisProcessor` validates readable text and language.
7. The processor skips work when a current completed index already exists and
   `force` is false.
8. The processor writes a `Processing` status before running the pipeline.
9. The default `BookPreprocessingPipeline` runs shared stages in order.
10. `PersistBookIndexStage` stores the completed index through
    `BookLibraryStore.replaceBookIndex(...)`.
11. On failure, the processor writes a `Failed` status with an error message.
12. Library rows and debug outputs consume the stored status and indexes.

Android debug folder processing is a platform debugging entrypoint. It may
force-process many EPUB files, but it still uses the same shared preprocessing
contracts.

## Processor contract

`BookAnalysisProcessor` is the shared entrypoint for per-book analysis.

`processBook(book, sections, force)` must:

- trim each text section;
- drop sections whose text becomes blank;
- write `Failed` when no readable text remains;
- detect language with `SimpleLanguageDetector`;
- currently accept only `DefaultAnalysisLanguage`, which is `en`;
- write `Failed` when no installed model exists for the detected language;
- compute the current `BookPreprocessingPipeline.fingerprint`;
- skip processing when `force` is false and `BookProcessingStore` reports a
  current completed index for the same book, language, provider, model version,
  index version, and pipeline fingerprint;
- write `Processing` before running stages;
- return the stored or newly completed `BookProcessingStatus`;
- catch stage failures and write `Failed`.

The processor owns status transitions. Individual stages should produce typed
outputs or throw failures; they should not implement their own freshness checks.

## Pipeline contract

`BookPreprocessingPipeline` is an ordered list of `BookPreprocessingStage`
instances.

Every stage must provide:

- `stageId`: stable lowercase letters, digits, and hyphens;
- `version`: a positive integer;
- `process(context)`: a transformation from one typed
  `BookPreprocessingContext` to the next.

`BookPreprocessingContext` is the typed shared data carrier for the pipeline.
It currently contains:

- `book`;
- `language`;
- `sections`;
- `pipelineFingerprint`;
- `startedAtMillis`;
- `processedAtMillis`;
- `analysis`;
- `lemmaCandidates`;
- `filteredLemmaCandidates`;
- `index`;
- `status`.

Stages must extend the typed context when they need new intermediate outputs.
Do not use generic maps, ad hoc string keys, platform objects, or untyped blobs
for pipeline data.

## Current default stages

The default shared pipeline is:

1. `UdpipeAnalysisStage`
2. `BuildLemmaCandidatesStage`
3. `FilterLemmaCandidatesStage`
4. `ScoreLemmaIndexStage`
5. `PersistBookIndexStage`

`UdpipeAnalysisStage` ensures the analysis model is available, calls the
platform `TextAnalysisProvider`, and stores successful token analysis in the
context. A `TextAnalysisResult.Failure` fails the book.

`docs/domains/native-udpipe-runtime.md` is the source of truth for UDPipe model
installation, native adapter ABI, Android JNI, iOS cinterop, build ownership,
and provider lifecycle rules.

`BuildLemmaCandidatesStage` requires completed text analysis, sets
`processedAtMillis`, and builds lemma candidates and chunk candidate counts
through `BookIndexBuilder`. Candidate statistics include real surface words
seen in the text for each normalized lemma key.

`FilterLemmaCandidatesStage` requires lemma candidates and removes lemmas that
should not enter the scored index.

`ScoreLemmaIndexStage` requires filtered candidates and builds the final
`BookIndex`.

`PersistBookIndexStage` requires a final index and processed timestamp, creates
a `Completed` `BookProcessingStatus`, and persists status plus index rows
through `BookProcessingStore.replaceBookIndex(...)`.

## Fingerprint contract

The pipeline fingerprint is the ordered `stageId@version` list joined by `|`.

The current default fingerprint is:

```text
udpipe-analysis@1|build-lemma-candidates@2|filter-lemma-candidates@1|score-lemma-index@2|persist-book-index@2
```

`DefaultBookPreprocessingPipelineFingerprint` must match the default pipeline.

The fingerprint changes when:

- a stage is added;
- a stage is removed;
- stages are reordered;
- a stage id changes;
- a stage version changes.

Change a stage version when its semantics or durable output change enough that
existing books must be reprocessed. Freshness checks must include the pipeline
fingerprint; do not add platform-only freshness logic.

## Index contract

The persisted per-book index is derived from shared analyzed tokens.

`ConlluParser` parses CoNLL-U output by:

- ignoring comments and blank lines;
- ignoring multiword-token ranges;
- ignoring empty nodes;
- keeping surface text, lemma, UPOS tag, token order, section id, and
  `TokenType` for regular token rows.

`BookIndexBuilder` builds candidate statistics from countable word tokens:

- tokens must have `TokenType.Word`;
- `PUNCT` and `SYM` are excluded;
- lemma keys are normalized;
- chunk ids are based on all countable word-like tokens;
- default chunk size is `DefaultBookIndexChunkSize`, currently `800`;
- candidate totals, UPOS counts, dominant UPOS, `PROPN` ratio, and optional
  global Zipf frequency are collected per lemma.
- candidate surface forms are collected from token surface text and ordered by
  descending surface count, then surface word.

`LemmaCandidateFilter` rejects candidates when:

- dominant UPOS is not `NOUN`, `VERB`, `ADJ`, or `ADV`;
- the normalized lemma is a UDPipe contraction fragment: `ca`, `wo`, `n't`,
  `'s`, `'re`, `'ve`, `'ll`, `'d`, or `'m`;
- the lemma contains digits;
- the lemma length after apostrophe normalization is less than `3`;
- `PROPN_count / total_count >= 0.4`;
- remaining characters after apostrophe normalization and apostrophe stripping
  are not all letters;
- global Zipf frequency is missing and `total_count < 5`.

`ScoreLemmaIndexStage` computes a TF-IDF-like rarity score for accepted lemmas
and builds:

- book-level lemma totals for `book_lemma_total`;
- lemma surface forms for `book_lemma_surface_form`;
- chunk-level accepted lemma counts for `book_chunk_lemma_count`.

The persisted user-facing vocabulary preview is capped by
`ImportantBookLemmaLimit`, currently `100`. Selection first ranks accepted
lemmas by `tfIdfScore` descending, then `totalCount` descending, then `lemma`
ascending, takes the top `100`, and then keeps only lemmas with
`totalCount > ImportantBookLemmaMinTotalCount`, currently `10`.

The saved book-level preview is ordered for UI display by `totalCount`
descending, then `tfIdfScore` descending, then `lemma` ascending. Lemmas remain
internal lookup keys; UI and export surfaces should use the stored surface-word
list when showing or matching real words from the book. Stored chunk lookup is
restricted to the selected lemmas and ordered by `localCount` descending, then
`lemma` ascending for a requested chunk.

## Persistence contract

`BookProcessingStore` is the shared store boundary used by the processor and
pipeline.

It exposes:

- `getProcessingStatus(bookId, language)`;
- `hasCurrentBookIndex(...)`;
- `upsertProcessingStatus(status)`;
- `replaceBookIndex(status, index)`.

`BookLibraryStore` implements this contract with SQLDelight.

The SQL schema stores:

- `book_analysis_metadata`: one status row per book and language;
- `book_lemma_total`: book-level lemma totals, global Zipf values, and scores;
- `book_lemma_surface_form`: real surface words and counts for each selected
  lemma;
- `book_chunk_lemma_count`: per-chunk lemma counts for accepted lemmas.

`replaceBookIndex(status, index)` must be atomic. It deletes old lemma rows for
the book and language, upserts the completed processing status, inserts fresh
book-level lemma totals, inserts fresh surface-form rows, and inserts fresh
chunk-level lemma counts.

Missing analysis metadata maps to `BookProcessingState.NotStarted` in
library-facing `BookItem` projections.

`docs/domains/database-storage.md` is the source of truth for `book.db`
schema migration and storage versioning rules.

## Platform boundary

Platform code starts preprocessing, extracts text, and provides native services.
Shared code owns preprocessing semantics.

Android owns:

- SAF URI access and permissions;
- Readium Kotlin publication opening;
- metadata and cover extraction;
- publication text extraction;
- Android UDPipe model access and native provider wiring;
- debug folder processing and debug lemma exports.

iOS Swift owns:

- security-scoped file access and bookmark persistence;
- Readium Swift Toolkit publication opening;
- metadata and cover extraction;
- publication text extraction;
- iOS UDPipe model access and native provider wiring;
- debug lemma exports.

Platform gateways may decide when to start preprocessing from import/open flows,
but they must use shared freshness checks and shared processing contracts. They
should not duplicate pipeline ordering, lemma filtering, scoring, or persistence
semantics.

Native UDPipe runtime details belong to
`docs/domains/native-udpipe-runtime.md`. This preprocessing document only owns
how native analysis output is consumed by the shared pipeline.

## Boundary with global frequency

Book preprocessing consumes bundled global Zipf frequency data during index
building. It does not own generation of that database.

Preprocessing-owned concerns:

- requesting Zipf values for candidate lemmas;
- using missing Zipf values in candidate filtering;
- using Zipf values in TF-IDF-like scoring;
- storing the consumed Zipf value with each persisted lemma total.

Global frequency database concerns:

- source corpus selection;
- database generation scripts;
- SQLite asset packaging;
- Android and iOS asset copy/open lifecycle;
- global DB leak and driver lifecycle rules.

`docs/domains/database-storage.md` owns storage and versioning rules for the
bundled frequency database. `docs/global-frequency-db.md` remains the build
guide for regenerating the asset.

## Adding or changing a stage

Before editing code, define the stage contract.

Document:

- purpose: what durable or transient result the stage produces;
- inputs: which `BookPreprocessingContext` fields must exist before it runs;
- outputs: which typed context field, store method, or SQL table it writes;
- failure behavior: whether failure should fail the book or degrade gracefully;
- position: exact location in the ordered default pipeline;
- version impact: whether existing books must be reprocessed;
- persistence owner: existing store method, new store method, or SQL migration;
- tests: stage, pipeline, processor, persistence, and regression coverage.

Implementation rules:

- add universal stages in `shared`;
- hide platform services behind shared contracts;
- give every stage a stable lowercase `stageId`;
- use a positive `version`;
- insert the stage only where its required inputs already exist;
- extend `BookPreprocessingContext` with typed fields;
- persist durable output through shared storage;
- update `DefaultBookPreprocessingPipelineFingerprint` when default stages or
  versions change;
- update this document in the same change.

## Consumers and debug output

The library UI consumes processing state from `BookItem`, including state,
token count, and error message. The reader `Words` modal consumes saved
important-word indexes through `BookLibraryStore.getLemmaCounts(bookId)`;
it does not recalculate frequencies and must display stored surface words
instead of raw UDPipe lemmas whenever surface words are available.

Debug builds may export processing logs and top lemma files after processing.
Android debug builds also expose folder processing from the library screen.
These are platform debugging tools, not shared preprocessing contracts.

## Verification

Use `docs/agent-harness.md` for the full verification matrix.

For shared preprocessing, parser, index, stage, processor, or fingerprint
changes, the default command is:

```bash
./gradlew :shared:test
```

Current common tests already cover the processor, pipeline, CoNLL-U parser,
index builder, and lemma filter. Add or update only the focused tests that
protect the stage, output, versioning, or persistence contract being changed.

Run native or platform smoke checks only when a preprocessing change also
touches model installation, `TextAnalysisProvider`, UDPipe output semantics,
Readium text extraction, or platform preprocessing startup.

## Test contract

`BookPreprocessingPipelineTest` should cover:

- stage id validation;
- duplicate stage id rejection;
- positive stage versions;
- ordered stage execution;
- default fingerprint matching `DefaultBookPreprocessingPipelineFingerprint`.

`BookPreprocessingStageTest` should cover each default stage:

- required input validation;
- output context fields;
- failure behavior;
- preservation of unrelated context fields.

`BookAnalysisProcessorTest` should cover:

- skipping processing when the current pipeline fingerprint exists;
- force reprocessing;
- no readable text failure;
- unsupported language failure;
- writing `Processing` before a successful run;
- completed status after success;
- failed status after stage failure.

`ConlluParserTest` should cover:

- normal token rows;
- comments and blank lines;
- multiword-token ranges;
- empty nodes;
- token type mapping.

`BookIndexBuilderTest` and `LemmaCandidateFilterTest` should cover:

- countable token rules;
- candidate statistics;
- chunking with the default chunk size;
- global Zipf lookup and missing-Zipf fallback;
- filter rejection rules;
- TF-IDF-like scoring;
- book-level ordering;
- chunk-level ordering.

`BookLibraryStoreTest` should cover:

- processing status mapping;
- missing metadata mapping to `NotStarted`;
- `hasCurrentBookIndex`;
- `upsertProcessingStatus`;
- atomic `replaceBookIndex`;
- lemma total ordering;
- chunk lemma ordering.

Platform helper tests should cover behavior that is extractable without a real
Readium runtime:

- text section splitting;
- metadata fallback title;
- stable id generation;
- cover path preservation;
- stale or missing processing trigger.

Real EPUB import, native UDPipe execution, and full Readium text extraction can
remain platform smoke or integration tests.

## Regression rules

Every preprocessing change must preserve these rules unless the change
explicitly documents a new contract:

- shared owns stage ordering and fingerprint semantics;
- platform code extracts text and provides native services only;
- freshness checks include provider, model version, index version, and pipeline
  fingerprint;
- durable pipeline outputs are persisted through shared storage;
- every new typed context output has a stage test;
- every durable output has store or migration coverage;
- every default stage or version change updates this document and the
  fingerprint constant.
