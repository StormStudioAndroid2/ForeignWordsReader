#!/usr/bin/env python3
"""Generate the bundled global frequency SQLite database from wordfreq."""

from __future__ import annotations

import argparse
import datetime as dt
import re
import sqlite3
from pathlib import Path

from wordfreq import top_n_list, zipf_frequency


DATABASE_VERSION = "1"
WORD_PATTERN = re.compile(r"[a-z][a-z'-]*")
IRREGULAR_ENGLISH_LEMMAS = {
    "am": "be",
    "are": "be",
    "is": "be",
    "was": "be",
    "were": "be",
    "been": "be",
    "being": "be",
    "has": "have",
    "had": "have",
    "having": "have",
    "does": "do",
    "did": "do",
    "doing": "do",
}


def normalize(value: str) -> str | None:
    normalized = value.strip().lower()
    if not normalized or normalized == "_":
        return None
    if not any(char.isalnum() for char in normalized):
        return None
    return normalized


def simple_english_lemma(value: str) -> str:
    word = IRREGULAR_ENGLISH_LEMMAS.get(value, value)
    if not WORD_PATTERN.fullmatch(word):
        return word
    if word.endswith("'s") and len(word) > 4:
        return word[:-2]
    if word.endswith("ies") and len(word) > 5:
        return word[:-3] + "y"
    if word.endswith("ing") and len(word) > 6:
        return undouble_final_consonant(word[:-3])
    if word.endswith("ed") and len(word) > 5:
        return undouble_final_consonant(word[:-2])
    if word.endswith(("ches", "shes", "sses", "xes", "zes")) and len(word) > 5:
        return word[:-2]
    if word.endswith("s") and not word.endswith("ss") and len(word) > 4:
        return word[:-1]
    return word


def undouble_final_consonant(value: str) -> str:
    if len(value) >= 2 and value[-1] == value[-2] and value[-1] not in "aeiou":
        return value[:-1]
    return value


def build_entries(language: str, limit: int) -> dict[str, tuple[float, str, int]]:
    entries: dict[str, tuple[float, str, int]] = {}
    for raw_word in top_n_list(language, limit):
        word = normalize(raw_word)
        if word is None:
            continue
        lemma = normalize(simple_english_lemma(word))
        if lemma is None:
            continue

        zipf = float(zipf_frequency(word, language))
        current = entries.get(lemma)
        if current is None:
            entries[lemma] = (zipf, word, 1)
        else:
            current_zipf, current_word, count = current
            if zipf > current_zipf:
                entries[lemma] = (zipf, word, count + 1)
            else:
                entries[lemma] = (current_zipf, current_word, count + 1)
    return entries


def write_database(output: Path, language: str, limit: int, entries: dict[str, tuple[float, str, int]]) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        output.unlink()

    connection = sqlite3.connect(output)
    try:
        connection.executescript(
            """
            PRAGMA journal_mode = OFF;
            PRAGMA synchronous = OFF;
            PRAGMA temp_store = MEMORY;

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
            """
        )
        metadata = {
            "source": "wordfreq",
            "database_version": DATABASE_VERSION,
            "supported_languages": language,
            "generated_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
            "word_limit": str(limit),
            "normalization_rules": "trim, lowercase, reject blank/_/no-letter-or-digit",
            "lemmatizer": "simple-english-compatible-with-runtime-lemma-keys",
        }
        connection.executemany(
            "INSERT INTO metadata(key, value) VALUES (?, ?)",
            sorted(metadata.items()),
        )
        connection.executemany(
            """
            INSERT INTO global_lemma_frequency(
              language,
              lemma,
              zipf_frequency,
              source_form,
              source_form_count
            )
            VALUES (?, ?, ?, ?, ?)
            """,
            (
                (language, lemma, zipf, source_form, source_form_count)
                for lemma, (zipf, source_form, source_form_count) in sorted(entries.items())
            ),
        )
        connection.commit()
        connection.execute("PRAGMA optimize")
        connection.execute("VACUUM")
    finally:
        connection.close()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--language", default="en")
    parser.add_argument("--limit", type=int, default=50_000)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    if args.language != "en":
        raise ValueError("Only English is supported for the MVP frequency database.")

    entries = build_entries(language=args.language, limit=args.limit)
    write_database(output=args.output, language=args.language, limit=args.limit, entries=entries)
    print(f"Wrote {len(entries)} lemma frequencies to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

