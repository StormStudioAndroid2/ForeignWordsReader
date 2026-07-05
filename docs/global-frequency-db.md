# Global Lemma Frequency DB

`scripts/generate-wordfreq-db.py` builds the bundled English lemma frequency
database for the reader app. The database is generated offline on a developer
machine and shipped as a bundled app asset.

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
