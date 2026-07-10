# Android global frequency SQLite leak

## Observed warning

During Android debug batch processing, logcat reported a leaked SQLite
connection after a book finished analysis:

```text
SQLiteConnectionPool W A SQLiteConnection object for database
'/data/user/0/com.example.myapplication.android/databases/global-frequency.sqlite'
was leaked! Please fix your application to end transactions in progress
properly and to close the database when it is no longer needed.
System W A resource failed to call SQLiteConnection.close.
System W A resource failed to call close.
```

This appeared immediately after a successful `Book analysis completed` line, so
the already completed book index was still persisted. The warning points at the
read-only bundled global frequency database, not at the app's writable book
index database.

## Current likely cause

`AndroidBookLibraryGateway.processBookNow(...)` creates a new global frequency
repository for each processed book:

```kotlin
globalFrequencyRepository = AndroidGlobalFrequencyRepositoryFactory(application).create()
```

`AndroidGlobalFrequencyRepositoryFactory.create()` creates a new SQLDelight
`AndroidSqliteDriver` backed by `FrameworkSQLiteOpenHelperFactory`, and
`SqlDelightGlobalFrequencyRepository` keeps that driver open while
`BookIndexBuilder` queries global Zipf frequencies.

The repository currently has no lifecycle method, so `processBookNow(...)`
cannot close the driver after `BookAnalysisProcessor.processBook(...)`
returns. In a folder batch this can leave one `SQLiteConnectionPool` per
processed book until finalization catches it.

## Can it break book analysis?

For an individual book, the warning usually appears after the global frequency
queries have already completed and after the book lemma index has been saved.
So it should not corrupt the TF-IDF output for books that reached
`Book analysis completed`.

For a long folder batch, it is a real stability risk:

- leaked SQLite handles can accumulate across books;
- Android may delay cleanup until GC/finalization;
- later books can become slower or fail to open the global frequency database;
- repeated warnings make real processing failures harder to notice in logs.

This should be fixed before using the prototype for larger book sets.

## Proposed fix

Add an explicit close lifecycle to the global frequency repository and close it
from the Android batch path after each book.

Recommended minimal shape:

1. Extend `GlobalFrequencyRepository` with a default no-op close method:

```kotlin
interface GlobalFrequencyRepository {
    fun getZipfFrequencies(
        language: String,
        lemmas: Set<String>,
    ): Map<String, Double>

    fun close() = Unit
}
```

2. Override it in `SqlDelightGlobalFrequencyRepository`:

```kotlin
class SqlDelightGlobalFrequencyRepository(
    private val driver: SqlDriver,
    private val batchSize: Int = DefaultBatchSize,
) : GlobalFrequencyRepository {
    override fun close() {
        driver.close()
    }
}
```

3. In `AndroidBookLibraryGateway.processBookNow(...)`, keep the repository in a
   local variable and close it in the same `finally` block as
   `AndroidTextAnalysisProvider`:

```kotlin
val modelRepository = AndroidModelRepository(application)
val analysisProvider = AndroidTextAnalysisProvider(modelRepository)
val globalFrequencyRepository = AndroidGlobalFrequencyRepositoryFactory(application).create()
val status = try {
    BookAnalysisProcessor(
        store = store,
        modelRepository = modelRepository,
        analysisProvider = analysisProvider,
        clockMillis = System::currentTimeMillis,
        globalFrequencyRepository = globalFrequencyRepository,
    ).processBook(
        book = book,
        sections = sections,
        force = force,
    )
} finally {
    analysisProvider.close()
    globalFrequencyRepository.close()
}
```

4. Apply the same close lifecycle to iOS call sites that create
   `IosGlobalFrequencyRepositoryFactory().create()`, so both platforms have the
   same ownership rule.

## Alternative

A longer-lived singleton repository per app process would also avoid creating a
new SQLite driver per book. For the debug batch prototype, explicit per-book
ownership is simpler and safer: create the repository for one processing run,
use it while building the index, then close it deterministically.

