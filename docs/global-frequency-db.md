# Global Lemma Frequency DB

`scripts/generate-wordfreq-db.py` builds the bundled English lemma frequency
database for the reader app. The database is generated offline on a developer
machine and shipped as a bundled app asset.

Storage ownership, runtime copy behavior, and versioning policy are documented
in `docs/domains/database-storage.md`. This file is the build guide for
regenerating the bundled asset.

## Responsibility

The global frequency DB build process owns:

- selecting the frequency input package and wordlist size;
- lemmatizing source forms with the same UDPipe English model family used by
  the app;
- aggregating form frequencies into lemma-level Zipf values;
- writing `global-frequency.sqlite`;
- writing metadata that lets app runtimes decide whether a bundled asset should
  replace an installed copy;
- providing builder tests and small generation smoke checks.

The generated database is consumed by book preprocessing scoring. It is not
user data and it is not migrated with SQLDelight.

## Out of scope

This document does not own:

- `book.db` schema or SQLDelight migrations;
- app runtime asset-copy lifecycle;
- Android or iOS storage paths;
- per-book preprocessing stage ordering;
- UDPipe native runtime ABI;
- reader or library UI behavior.

Those rules live in `docs/domains/database-storage.md`,
`docs/domains/book-preprocessing.md`, and
`docs/domains/native-udpipe-runtime.md`.

## Build

Install the pinned builder dependencies:

```bash
python3 -m pip install -r scripts/requirements-wordfreq-builder.txt
```

Place the same UDPipe English model used by the app at:

```text
shared/src/main/assets/udpipe/english-ewt.udpipe
```

Then generate the bundled frequency database:

```bash
python3 scripts/generate-wordfreq-db.py
```

By default this writes:

```text
shared/src/main/assets/frequency/global-frequency.sqlite
```

For local experiments, use `--limit` to process only the first N wordfreq forms:

```bash
python3 scripts/generate-wordfreq-db.py --limit 10000 --output /tmp/global-frequency.sqlite
```

## Contract

This is a new database build, not a migration. Client developers should replace
the bundled asset with the newly generated `global-frequency.sqlite`.

The stable lookup query is:

```sql
SELECT zipf_frequency
FROM global_lemma_frequency
WHERE language = ? AND lemma = ?;
```

The app should pass lowercase UDPipe lemmas to the query. The builder uses
`wordfreq` as the frequency source and UDPipe as the lemmatizer, then stores one
row per `(language, lemma)`.

Important metadata:

- `database_version`: currently `3`
- `wordfreq_wordlist`: expected `large`
- `lemmatizer`: expected `udpipe`
- `udpipe_model_sha256`: SHA-256 of the model used for generation
- `aggregation`: `linear-frequency-sum-to-zipf`
- `lemma_count`, `wordfreq_form_count`, and skipped counters for QA

If an app target copies this asset into internal storage, it must compare
`metadata.database_version` and recopy the bundled asset when the version
changes. No SQL migration is required for this asset.

## Test and verification

For builder logic changes, run:

```bash
python3 -m unittest scripts/test_generate_wordfreq_db.py
```

For a small output smoke check, run:

```bash
python3 scripts/generate-wordfreq-db.py --limit 10000 --output /tmp/global-frequency.sqlite
```

For a full shipped rebuild, also verify:

- `metadata.database_version` matches the intended shipped version;
- both Android and iOS bundled assets are replaced when the generated output is
  meant to ship;
- platform expected version constants match the generated metadata;
- `docs/domains/database-storage.md` still describes the runtime copy and
  versioning behavior accurately.

Do not run a full database rebuild for ordinary documentation-only changes.

## Change checklist

Before changing global frequency generation, decide:

- whether the source package, wordlist, language, UDPipe model, normalization,
  aggregation, schema, or metadata contract changes;
- whether `DATABASE_VERSION` must be bumped so installed app copies are
  replaced;
- whether preprocessing scoring or filtering semantics are affected;
- whether the generated asset should be rebuilt for both bundled platform
  copies;
- whether builder tests need new cases for the changed rule;
- whether storage documentation needs a versioning update.

If output semantics change because the UDPipe model or lemmatization behavior
changed, also update `docs/domains/native-udpipe-runtime.md` and
`docs/domains/book-preprocessing.md` as needed.
