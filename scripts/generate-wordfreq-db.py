#!/usr/bin/env python3
"""Build the bundled English lemma frequency SQLite database.

The builder is intentionally offline: it consumes the wordfreq package data and
an already downloaded UDPipe English model, then writes a SQLite asset that can
be bundled into the Android app.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import importlib
import importlib.metadata
import logging
import math
import os
import sqlite3
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from types import ModuleType
from typing import Iterable, Protocol, Sequence


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = PROJECT_ROOT / "shared/src/main/assets/frequency/global-frequency.sqlite"
DEFAULT_UDPIPE_MODEL = PROJECT_ROOT / "shared/src/main/assets/udpipe/english-ewt.udpipe"
DEFAULT_WORDLIST = "large"
DEFAULT_BATCH_SIZE = 2_000
DATABASE_VERSION = "3"
INSTALL_HINT = "python3 -m pip install -r scripts/requirements-wordfreq-builder.txt"


class BuilderError(RuntimeError):
    """Raised when the offline builder cannot proceed."""


class Lemmatizer(Protocol):
    def lemmatize(self, forms: Sequence[str]) -> list[str | None]:
        """Return one lemma per source form."""


@dataclass
class LemmaAccumulator:
    linear_frequency: float
    source_form: str
    source_form_frequency: float
    source_form_count: int

    def add(self, source_form: str, linear_frequency: float) -> None:
        self.linear_frequency += linear_frequency
        self.source_form_count += 1
        if (
            linear_frequency > self.source_form_frequency
            or (
                linear_frequency == self.source_form_frequency
                and source_form < self.source_form
            )
        ):
            self.source_form = source_form
            self.source_form_frequency = linear_frequency


@dataclass
class BuildStats:
    wordfreq_form_count: int = 0
    accepted_source_form_count: int = 0
    skipped_source_form_count: int = 0
    skipped_missing_frequency_count: int = 0
    skipped_duplicate_source_form_count: int = 0
    skipped_lemma_count: int = 0
    lemma_count: int = 0


@dataclass
class BuildResult:
    entries: dict[str, tuple[float, str, int]]
    stats: BuildStats


class UDPipeLemmatizer:
    """Lemmatizes pre-tokenized source forms with a full UDPipe model."""

    def __init__(self, model_path: Path) -> None:
        if not model_path.is_file():
            raise BuilderError(
                "UDPipe model not found: "
                f"{model_path}. Put english-ewt.udpipe there or pass --udpipe-model."
            )

        try:
            from ufal.udpipe import Model, Pipeline, ProcessingError
        except ImportError as error:
            raise BuilderError(
                "Missing Python package ufal.udpipe. Install builder dependencies with "
                f"`{INSTALL_HINT}`."
            ) from error

        model = Model.load(str(model_path))
        if not model:
            raise BuilderError(f"Could not load UDPipe model: {model_path}")

        parser_none = getattr(Pipeline, "NONE", "none")
        self._pipeline = Pipeline(
            model,
            "vertical",
            Pipeline.DEFAULT,
            parser_none,
            "conllu",
        )
        self._processing_error = ProcessingError

    def lemmatize(self, forms: Sequence[str]) -> list[str | None]:
        if not forms:
            return []

        text = "".join(f"{form}\n\n" for form in forms)
        error = self._processing_error()
        processed = self._pipeline.process(text, error)
        if error.occurred():
            raise BuilderError(f"UDPipe failed: {error.message}")

        lemmas = parse_conllu_lemmas(processed)
        if len(lemmas) != len(forms):
            raise BuilderError(
                "UDPipe returned an unexpected number of lemmas: "
                f"expected {len(forms)}, got {len(lemmas)}."
            )
        return lemmas


def parse_conllu_lemmas(conllu: str) -> list[str | None]:
    lemmas: list[str | None] = []
    for line in conllu.splitlines():
        if not line or line.startswith("#"):
            continue

        columns = line.split("\t")
        if len(columns) < 3:
            continue

        token_id = columns[0]
        if "-" in token_id or "." in token_id:
            continue

        lemmas.append(columns[2])
    return lemmas


def normalize_token(value: str | None) -> str | None:
    if value is None:
        return None

    normalized = value.strip().lower()
    if not normalized or normalized == "_":
        return None

    if any(char.isspace() or unicodedata.category(char)[0] == "C" for char in normalized):
        return None

    if not any(char.isalnum() for char in normalized):
        return None

    return normalized


def linear_frequency_to_zipf(linear_frequency: float) -> float:
    if linear_frequency <= 0:
        raise ValueError("Frequency must be positive.")
    return math.log10(linear_frequency) + 9.0


def load_wordfreq_module() -> ModuleType:
    try:
        return importlib.import_module("wordfreq")
    except ImportError as error:
        raise BuilderError(
            "Missing Python package wordfreq. Install builder dependencies with "
            f"`{INSTALL_HINT}`."
        ) from error


def package_version(*distribution_names: str) -> str:
    for distribution_name in distribution_names:
        try:
            return importlib.metadata.version(distribution_name)
        except importlib.metadata.PackageNotFoundError:
            continue
    return "unknown"


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def iter_wordfreq_forms(
    wordfreq_module: ModuleType,
    language: str,
    wordlist: str,
    limit: int | None,
) -> Iterable[str]:
    try:
        iterator = wordfreq_module.iter_wordlist(language, wordlist=wordlist)
    except AttributeError as error:
        raise BuilderError("wordfreq.iter_wordlist is required but was not found.") from error

    for index, raw_form in enumerate(iterator):
        if limit is not None and index >= limit:
            break
        yield raw_form


def get_frequency_dict(
    wordfreq_module: ModuleType,
    language: str,
    wordlist: str,
) -> dict[str, float]:
    try:
        frequencies = wordfreq_module.get_frequency_dict(language, wordlist=wordlist)
    except AttributeError as error:
        raise BuilderError("wordfreq.get_frequency_dict is required but was not found.") from error
    return dict(frequencies)


def build_entries(
    *,
    language: str,
    wordlist: str,
    limit: int | None,
    lemmatizer: Lemmatizer,
    batch_size: int = DEFAULT_BATCH_SIZE,
    wordfreq_module: ModuleType | None = None,
    logger: logging.Logger | None = None,
) -> BuildResult:
    if batch_size <= 0:
        raise ValueError("batch_size must be positive.")

    logger = logger or logging.getLogger(__name__)
    wordfreq_module = wordfreq_module or load_wordfreq_module()
    frequencies = get_frequency_dict(wordfreq_module, language=language, wordlist=wordlist)

    stats = BuildStats()
    seen_source_forms: set[str] = set()
    accumulators: dict[str, LemmaAccumulator] = {}
    batch_forms: list[str] = []
    batch_frequencies: list[float] = []

    def process_batch() -> None:
        if not batch_forms:
            return

        lemmas = lemmatizer.lemmatize(batch_forms)
        if len(lemmas) != len(batch_forms):
            raise BuilderError(
                "Lemmatizer returned an unexpected number of lemmas: "
                f"expected {len(batch_forms)}, got {len(lemmas)}."
            )

        for source_form, linear_frequency, raw_lemma in zip(
            batch_forms,
            batch_frequencies,
            lemmas,
        ):
            lemma = normalize_token(raw_lemma)
            if lemma is None:
                stats.skipped_lemma_count += 1
                continue

            current = accumulators.get(lemma)
            if current is None:
                accumulators[lemma] = LemmaAccumulator(
                    linear_frequency=linear_frequency,
                    source_form=source_form,
                    source_form_frequency=linear_frequency,
                    source_form_count=1,
                )
            else:
                current.add(source_form, linear_frequency)

        logger.info(
            "Processed %d wordfreq forms, accepted %d, current lemmas %d",
            stats.wordfreq_form_count,
            stats.accepted_source_form_count,
            len(accumulators),
        )
        batch_forms.clear()
        batch_frequencies.clear()

    for raw_form in iter_wordfreq_forms(
        wordfreq_module,
        language=language,
        wordlist=wordlist,
        limit=limit,
    ):
        stats.wordfreq_form_count += 1
        source_form = normalize_token(str(raw_form))
        if source_form is None:
            stats.skipped_source_form_count += 1
            continue

        if source_form in seen_source_forms:
            stats.skipped_duplicate_source_form_count += 1
            continue
        seen_source_forms.add(source_form)

        linear_frequency = frequencies.get(str(raw_form))
        if linear_frequency is None:
            linear_frequency = frequencies.get(source_form)
        if linear_frequency is None or linear_frequency <= 0:
            stats.skipped_missing_frequency_count += 1
            continue

        stats.accepted_source_form_count += 1
        batch_forms.append(source_form)
        batch_frequencies.append(float(linear_frequency))

        if len(batch_forms) >= batch_size:
            process_batch()

    process_batch()

    entries = {
        lemma: (
            linear_frequency_to_zipf(accumulator.linear_frequency),
            accumulator.source_form,
            accumulator.source_form_count,
        )
        for lemma, accumulator in accumulators.items()
    }
    stats.lemma_count = len(entries)
    return BuildResult(entries=entries, stats=stats)


def write_database(
    *,
    output: Path,
    language: str,
    wordlist: str,
    limit: int | None,
    entries: dict[str, tuple[float, str, int]],
    stats: BuildStats,
    metadata: dict[str, str],
) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    temp_output = output.with_name(f".{output.name}.{os.getpid()}.tmp")
    if temp_output.exists():
        temp_output.unlink()

    connection = sqlite3.connect(temp_output)
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

        complete_metadata = {
            "source": "wordfreq",
            "database_version": DATABASE_VERSION,
            "supported_languages": language,
            "generated_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
            "wordfreq_wordlist": wordlist,
            "word_limit": "none" if limit is None else str(limit),
            "normalization_rules": (
                "trim, lowercase, reject blank/_/punctuation-only/"
                "whitespace-containing/control-containing tokens"
            ),
            "lemmatizer": "udpipe",
            "aggregation": "linear-frequency-sum-to-zipf",
            "wordfreq_form_count": str(stats.wordfreq_form_count),
            "accepted_source_form_count": str(stats.accepted_source_form_count),
            "skipped_source_form_count": str(stats.skipped_source_form_count),
            "skipped_missing_frequency_count": str(stats.skipped_missing_frequency_count),
            "skipped_duplicate_source_form_count": str(stats.skipped_duplicate_source_form_count),
            "skipped_lemma_count": str(stats.skipped_lemma_count),
            "lemma_count": str(stats.lemma_count),
            **metadata,
        }
        connection.executemany(
            "INSERT INTO metadata(key, value) VALUES (?, ?)",
            sorted(complete_metadata.items()),
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

        integrity = connection.execute("PRAGMA integrity_check").fetchone()
        if integrity is None or integrity[0] != "ok":
            raise BuilderError(f"SQLite integrity check failed: {integrity}")

        connection.execute("PRAGMA optimize")
        connection.execute("VACUUM")
    except Exception:
        connection.close()
        if temp_output.exists():
            temp_output.unlink()
        raise
    else:
        connection.close()
        os.replace(temp_output, output)


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return parsed


def configure_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper()),
        format="%(asctime)s %(levelname)s %(message)s",
        force=True,
    )


def create_lemmatizer(model_path: Path) -> Lemmatizer:
    return UDPipeLemmatizer(model_path)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build the bundled English global lemma frequency SQLite database.",
    )
    parser.add_argument("--language", default="en")
    parser.add_argument("--wordlist", default=DEFAULT_WORDLIST)
    parser.add_argument("--limit", type=positive_int)
    parser.add_argument("--batch-size", type=positive_int, default=DEFAULT_BATCH_SIZE)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--udpipe-model", type=Path, default=DEFAULT_UDPIPE_MODEL)
    parser.add_argument(
        "--log-level",
        default="INFO",
        choices=["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"],
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    configure_logging(args.log_level)
    logger = logging.getLogger(__name__)

    if args.language != "en":
        raise ValueError("Only English is supported for the MVP frequency database.")

    try:
        logger.info(
            "Building global frequency DB: language=%s wordlist=%s limit=%s batch_size=%d",
            args.language,
            args.wordlist,
            "none" if args.limit is None else args.limit,
            args.batch_size,
        )
        logger.info("Using UDPipe model: %s", args.udpipe_model)
        lemmatizer = create_lemmatizer(args.udpipe_model)
        result = build_entries(
            language=args.language,
            wordlist=args.wordlist,
            limit=args.limit,
            lemmatizer=lemmatizer,
            batch_size=args.batch_size,
            logger=logger,
        )
        metadata = {
            "wordfreq_package_version": package_version("wordfreq"),
            "udpipe_package_version": package_version("ufal.udpipe", "ufal-udpipe"),
            "udpipe_model_path": str(args.udpipe_model),
            "udpipe_model_sha256": file_sha256(args.udpipe_model),
        }
        write_database(
            output=args.output,
            language=args.language,
            wordlist=args.wordlist,
            limit=args.limit,
            entries=result.entries,
            stats=result.stats,
            metadata=metadata,
        )
    except BuilderError as error:
        logger.error("%s", error)
        return 2

    logger.info(
        "Wrote %d lemma frequencies to %s",
        result.stats.lemma_count,
        args.output,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
