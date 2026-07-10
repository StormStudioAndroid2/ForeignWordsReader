# Database storage domain

This document describes how ForeignWordsReader stores local database state, how
database versions are represented, and how to decide whether a change needs a
SQLDelight migration, an asset rebuild, or only a preprocessing version bump.

The project currently has two SQLite-backed storage surfaces with different
ownership and update rules:

- `book.db`: the writable app database owned by SQLDelight.
- `global-frequency.sqlite`: a generated, bundled, read-only frequency asset
  copied into app storage at runtime.

Do not treat these databases as one migration system. They have different
version numbers, different update paths, and different compatibility risks.

## Responsibility

The database storage domain is responsible for:

- documenting which local databases exist;
- documenting where each database is stored and opened;
- defining when schema or asset changes are required;
- defining how app data migrations are added;
- defining how generated database assets are versioned and replaced;
- protecting installed user data during updates;
- documenting lifecycle rules for database drivers and repositories;
- defining tests and release checks for database changes.

The main source files are:

- `shared/src/commonMain/sqldelight/com/example/myapplication/shared/data/BookDatabase.sq`
- `shared/src/commonMain/sqldelight/com/example/myapplication/shared/data/1.sqm`
- `shared/src/commonMain/sqldelight/com/example/myapplication/shared/data/2.sqm`
- `shared/src/commonMain/sqldelight/com/example/myapplication/shared/data/3.sqm`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/data/BookLibraryStore.kt`
- `shared/src/androidMain/kotlin/com/example/myapplication/shared/data/AndroidBookLibraryStoreFactory.kt`
- `shared/src/iosMain/kotlin/com/example/myapplication/shared/data/IosBookLibraryStoreFactory.kt`
- `shared/src/commonMain/kotlin/com/example/myapplication/shared/processing/GlobalFrequencyRepository.kt`
- `shared/src/androidMain/kotlin/com/example/myapplication/shared/processing/AndroidGlobalFrequencyRepository.kt`
- `shared/src/iosMain/kotlin/com/example/myapplication/shared/processing/IosGlobalFrequencyRepository.kt`
- `scripts/generate-wordfreq-db.py`

## Out of scope

This domain does not own:

- library UI behavior;
- reader UI behavior;
- preprocessing pipeline semantics;
- UDPipe native runtime behavior;
- source corpus selection for generated frequency data;
- app-level navigation;
- platform file picker permissions.

Those responsibilities belong to the library, reader, preprocessing, native
runtime, global frequency generation, root architecture, or platform app
domains.

## Current database map

### `book.db`

`book.db` is the writable local app database.

It stores:

- local library book metadata;
- per-book processing metadata;
- persisted book-level lemma totals;
- persisted per-chunk lemma counts.

Source of truth:

- schema and queries: `BookDatabase.sq`;
- migrations: numbered `.sqm` files next to the schema;
- Kotlin store mapping: `BookLibraryStore`.

Runtime locations:

- Android opens it through `AndroidSqliteDriver` with name `book.db`.
- iOS opens it through `NativeSqliteDriver` with name `book.db`.

Current SQLDelight schema version:

- `4`

The generated schema version is derived from SQLDelight inputs. Do not edit
generated files to change it.

### `global-frequency.sqlite`

`global-frequency.sqlite` is a generated frequency lookup database asset.

It stores:

- `metadata`: generation and compatibility metadata;
- `global_lemma_frequency`: one row per `(language, lemma)` with Zipf
  frequency and source-form information.

Source of truth:

- builder script: `scripts/generate-wordfreq-db.py`;
- build guide: `docs/global-frequency-db.md`;
- Android bundled asset:
  `shared/src/main/assets/frequency/global-frequency.sqlite`;
- iOS bundled asset:
  `app-ios-swift/app-ios-swift/Resources/frequency/global-frequency.sqlite`.

Runtime locations:

- Android copies the asset to the app database directory as
  `global-frequency.sqlite`.
- iOS copies the asset to Application Support under `frequency`.

Current generated database content version:

- `metadata.database_version = 3`

Android and iOS compare this metadata value against their expected version
constant before deciding whether to recopy the bundled asset.

## App database contract

`book.db` is user data. It must be migrated, not silently replaced, unless a
destructive reset is explicitly documented and accepted for the affected build.

The current schema stores:

- `book`: stable book id, EPUB URI, title, author, cover URI, last-opened time;
- `book_analysis_metadata`: processing state and analysis freshness metadata;
- `book_lemma_total`: book-level lemma counts, global Zipf values, scores;
- `book_chunk_lemma_count`: accepted lemma counts per book chunk.

`BookLibraryStore` is the shared access layer for this database. Feature code
should use store methods instead of hand-written SQL outside the storage layer.

Current migration history:

- `1.sqm`: adds processing metadata and initial lemma/chunk index tables.
- `2.sqm`: adds global frequency and TF-IDF score columns to lemma totals.
- `3.sqm`: adds `pipeline_fingerprint` to processing metadata and freshness
  lookup.

The current `BookDatabase.sq` schema already includes all migrations above for
new installs.

## App database versioning

`book.db` uses SQLDelight schema versioning.

Current state:

- source schema: `BookDatabase.sq`;
- migration files: `1.sqm`, `2.sqm`, `3.sqm`;
- generated schema version: `4`.

Rules:

- Add a new `.sqm` file for every schema change that affects installed users.
- Keep `BookDatabase.sq` as the complete schema for new installs.
- Never edit old migration files after they have shipped.
- Never rely on app version name or app version code as the database schema
  version.
- Generated SQLDelight files are build outputs, not source of truth.

## When to change `book.db`

Change `book.db` when durable app state changes.

Examples that require a schema or migration decision:

- adding a persisted field to `BookItem`;
- storing reader position in shared SQL instead of platform preferences;
- adding bookmarks, notes, highlights, vocabulary state, or learning state;
- changing processing status columns;
- changing persisted lemma index shape;
- adding a new table for reader or learning features;
- changing indexes needed for query performance;
- changing primary keys or uniqueness rules.

Examples that usually do not require a `book.db` schema change:

- changing UI-only state;
- adding a reader overlay with no durable data;
- changing preprocessing stage order when durable tables stay compatible;
- changing only the preprocessing pipeline fingerprint;
- rebuilding `global-frequency.sqlite`;
- changing debug export text files.

If a change affects durable semantics but not table shape, decide whether it
needs a data migration, an index version bump, a pipeline fingerprint change, or
a documented destructive recomputation.

## How to change `book.db`

Use this sequence for app database changes:

1. Define the durable concept and owning domain.
2. Decide whether existing installed data must be preserved, backfilled,
   recomputed, or intentionally dropped.
3. Update `BookDatabase.sq` for new installs.
4. Add the next numbered `.sqm` migration for existing installs.
5. Update `BookLibraryStore` or a new store boundary.
6. Update domain models and component models that expose the data.
7. Add or update store tests for mapping, ordering, null/default behavior, and
   migrations.
8. Update the relevant domain document and this storage document.
9. Run the SQLDelight generation and relevant shared tests.

Migration rules:

- Use additive migrations when possible.
- Provide defaults for non-null columns.
- Backfill derived values deterministically.
- Preserve user-visible data unless a destructive change is explicitly
  documented.
- Keep foreign keys and indexes aligned with query patterns.
- Add indexes together with queries that need them.
- Treat primary key changes as high risk and document rollout behavior.

## Generated frequency database contract

`global-frequency.sqlite` is not user-authored data. It is a generated asset.
The app can replace it when the bundled `metadata.database_version` changes.

Current schema:

```sql
CREATE TABLE metadata (
  key TEXT NOT NULL PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE global_lemma_frequency (
  language TEXT NOT NULL,
  lemma TEXT NOT NULL,
  zipf_frequency REAL NOT NULL,
  source_form TEXT NOT NULL,
  source_form_count INTEGER NOT NULL,
  PRIMARY KEY (language, lemma)
) WITHOUT ROWID;
```

Stable lookup query:

```sql
SELECT lemma, zipf_frequency
FROM global_lemma_frequency
WHERE language = ?
  AND lemma IN (...);
```

Runtime code must pass normalized lowercase lemmas. The shared
`SqlDelightGlobalFrequencyRepository` performs normalization before querying.

## Generated frequency versioning

`global-frequency.sqlite` uses metadata versioning, not SQLDelight migrations.

Current state:

- builder constant: `DATABASE_VERSION = 3`;
- asset metadata: `metadata.database_version = 3`;
- Android expected asset version: `ExpectedFrequencyDatabaseVersion = "3"`;
- iOS expected asset version: `ExpectedFrequencyDatabaseVersion = "3"`.

The Android `SupportSQLiteOpenHelper.Callback(version = 1)` is only the SQLite
open-helper version for the copied file. It is not the content compatibility
version. The content version is `metadata.database_version`.

iOS opens this database with `NO_VERSION_CHECK`; compatibility is also governed
by `metadata.database_version`.

## When to rebuild or version `global-frequency.sqlite`

Rebuild the asset when generated content changes.

Bump `DATABASE_VERSION` when any of these change:

- SQLite schema;
- metadata contract;
- source frequency package or wordlist semantics;
- UDPipe model used for generation;
- lemmatization rules;
- normalization rules;
- aggregation algorithm;
- supported languages;
- meaning of `zipf_frequency`;
- rows that shipped to users should be replaced on app update.

Do not bump `DATABASE_VERSION` for local experiments using `--limit` unless the
result will be bundled in the app.

## How to change `global-frequency.sqlite`

Use this sequence for generated frequency database changes:

1. Update `scripts/generate-wordfreq-db.py`.
2. Update `scripts/test_generate_wordfreq_db.py`.
3. Bump `DATABASE_VERSION` when shipped content compatibility changes.
4. Rebuild the asset with `python3 scripts/generate-wordfreq-db.py`.
5. Replace both bundled assets:
   - `shared/src/main/assets/frequency/global-frequency.sqlite`;
   - `app-ios-swift/app-ios-swift/Resources/frequency/global-frequency.sqlite`.
6. Update Android and iOS expected version constants.
7. Verify metadata in the generated database.
8. Update `docs/global-frequency-db.md` and this document.
9. Run builder tests and relevant shared preprocessing tests.

No `.sqm` migration is required for this asset. Installed copies are replaced
by runtime asset recopy when metadata version changes.

## Runtime install and lifecycle rules

`book.db`:

- is opened as the app writable SQLDelight database;
- should stay compatible across app updates through SQLDelight migrations;
- should not be deleted to solve ordinary schema changes.

`global-frequency.sqlite`:

- is copied from bundled resources into app storage;
- is recopied when the installed metadata version does not match the expected
  bundled metadata version;
- should be treated as read-only lookup data;
- must have a clear repository lifecycle.

`GlobalFrequencyRepository.close()` exists so platform code can close the
underlying SQL driver. Call it when a per-processing repository is no longer
needed. Long-lived repositories must have an explicit owner that closes them
with the app or feature lifecycle.

The Android leak note in `docs/android-global-frequency-sqlite-leak.md`
documents why this lifecycle matters.

## Decision table

Use this table before changing storage:

| Change | Storage action |
| --- | --- |
| Add persisted book metadata | Add `book.db` migration and store tests |
| Add persisted processing metadata | Add `book.db` migration, freshness tests, domain docs |
| Change lemma index table shape | Add `book.db` migration and preprocessing/store tests |
| Change lemma scoring only | Usually bump index version or pipeline fingerprint, not schema |
| Change preprocessing stage order | Update pipeline fingerprint; schema only if stored shape changes |
| Change global frequency generation algorithm | Rebuild asset and bump `database_version` |
| Change global frequency SQLite schema | Rebuild asset and bump `database_version` |
| Add app-wide vocabulary state | Add new app DB table and owning domain doc |
| Add temporary reader overlay state | No database change |
| Add debug-only export files | No app DB migration; document debug file ownership |

## Destructive change policy

Avoid destructive changes to `book.db`.

Destructive changes require explicit documentation of:

- which user data is lost;
- why migration or recomputation is not practical;
- whether the change is debug-only, prototype-only, or release-safe;
- how users recover;
- which tests prove the destructive path is intentional.

Generated assets are different. `global-frequency.sqlite` can be replaced when
the asset version changes because it is generated lookup data, not user data.

## Backup, privacy, and data ownership

`book.db` may contain:

- EPUB URI strings;
- book titles and authors;
- cover URI strings;
- reading-related recency;
- processing status and errors;
- derived lemma indexes from local books.

Treat this as local user data. Do not export, upload, or log it unless the
owning feature explicitly documents that behavior.

Debug exports may contain book titles, book ids, lemma lists, and processing
errors. Keep debug export ownership in platform/debug documentation, not in the
shared storage contract.

`global-frequency.sqlite` contains generated corpus-level frequency data and no
user book content.

## Test contract

`BookLibraryStoreTest` should cover:

- book insert/update/read behavior;
- recent ordering;
- processing status mapping;
- missing metadata mapping to `NotStarted`;
- `hasCurrentBookIndex`;
- atomic `replaceBookIndex`;
- lemma total ordering;
- chunk lemma ordering;
- any new SQL query or store mapping.

SQLDelight migration tests should cover:

- each new migration from the previous schema version;
- non-null defaults;
- data preservation;
- index and foreign-key expectations;
- old rows with missing optional data.

Global frequency builder tests should cover:

- generated schema;
- required metadata keys;
- `database_version`;
- normalization behavior;
- aggregation behavior;
- atomic database writes;
- integrity checks.

Runtime asset tests or smoke checks should cover:

- Android recopy when metadata version changes;
- iOS recopy when metadata version changes;
- lookup of a known lemma;
- repository close behavior when a per-run repository is created.

## Verification

Use `docs/agent-harness.md` for the full verification matrix.

For shared store, query, migration, or storage mapping changes, the default
shared command is:

```bash
./gradlew :shared:test
```

For global-frequency builder changes, run:

```bash
python3 -m unittest scripts/test_generate_wordfreq_db.py
```

For a small generated-output smoke check, run:

```bash
python3 scripts/generate-wordfreq-db.py --limit 10000 --output /tmp/global-frequency.sqlite
```

Run Android or iOS platform checks when storage changes affect runtime asset
copy paths, bundled database resources, SQL driver lifecycle, or platform
expected frequency database versions.

## Release checklist

Before shipping a database change:

1. Identify whether the change touches `book.db`, `global-frequency.sqlite`, or
   both.
2. For `book.db`, confirm the next `.sqm` migration exists when schema changed.
3. For `book.db`, confirm installed data is migrated or intentionally reset.
4. For `global-frequency.sqlite`, confirm `metadata.database_version` and
   platform expected versions match.
5. Confirm Android and iOS bundled assets are both updated when the frequency
   asset changes.
6. Run store, migration, builder, and preprocessing tests that cover the change.
7. Update this document and the owning domain document.
8. Mention any intentionally skipped migration, destructive reset, or platform
   parity gap in release notes or the relevant domain doc.

## Regression rules

- Do not add persistent user data without a storage owner.
- Do not change `book.db` schema without a migration plan.
- Do not edit shipped migration files.
- Do not rely on generated SQLDelight files as source.
- Do not replace `book.db` to avoid a migration.
- Do not change generated frequency content without deciding whether
  `database_version` must change.
- Do not update only one platform copy of `global-frequency.sqlite`.
- Do not leave SQL drivers open when repository ownership is per operation.
