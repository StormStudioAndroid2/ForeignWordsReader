#!/usr/bin/env python3
"""Tests for generate-wordfreq-db.py."""

from __future__ import annotations

import contextlib
import importlib.util
import io
import logging
import math
import sqlite3
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock


SCRIPT_PATH = Path(__file__).with_name("generate-wordfreq-db.py")


def load_generator_module():
    spec = importlib.util.spec_from_file_location("generate_wordfreq_db", SCRIPT_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Could not load {SCRIPT_PATH}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def fake_wordfreq_module(words: list[str], frequencies: dict[str, float]) -> types.ModuleType:
    module = types.ModuleType("wordfreq")

    def iter_wordlist(language: str, wordlist: str = "best"):
        return iter(words)

    def get_frequency_dict(language: str, wordlist: str = "best") -> dict[str, float]:
        return frequencies

    def top_n_list(language: str, limit: int):
        raise AssertionError("top_n_list must not be used by the production builder")

    module.iter_wordlist = iter_wordlist
    module.get_frequency_dict = get_frequency_dict
    module.top_n_list = top_n_list
    return module


class FakeLemmatizer:
    def __init__(self, lemmas: dict[str, str | None]) -> None:
        self.lemmas = lemmas
        self.calls: list[list[str]] = []

    def lemmatize(self, forms):
        self.calls.append(list(forms))
        return [self.lemmas.get(form, form) for form in forms]


def has_module(name: str) -> bool:
    try:
        return importlib.util.find_spec(name) is not None
    except ModuleNotFoundError:
        return False


class NormalizeTokenTest(unittest.TestCase):
    def setUp(self) -> None:
        self.generator = load_generator_module()

    def test_lowercases_and_trims(self) -> None:
        self.assertEqual("always", self.generator.normalize_token(" Always "))

    def test_rejects_blank_underscore_punctuation_whitespace_and_control(self) -> None:
        self.assertIsNone(self.generator.normalize_token(""))
        self.assertIsNone(self.generator.normalize_token("   "))
        self.assertIsNone(self.generator.normalize_token("_"))
        self.assertIsNone(self.generator.normalize_token("!!!"))
        self.assertIsNone(self.generator.normalize_token("two words"))
        self.assertIsNone(self.generator.normalize_token("line\nbreak"))
        self.assertIsNone(self.generator.normalize_token("zero\u0000byte"))

    def test_preserves_valid_token_characters_without_stemming(self) -> None:
        self.assertEqual("o'clock", self.generator.normalize_token("O'Clock"))
        self.assertEqual("word.", self.generator.normalize_token("word."))
        self.assertEqual("co-operate", self.generator.normalize_token("Co-Operate"))


class ConlluParsingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.generator = load_generator_module()

    def test_extracts_word_lemmas_and_ignores_comments_and_multiword_rows(self) -> None:
        conllu = "\n".join(
            [
                "# sent_id = 1",
                "1-2\tdon't\t_\t_\t_\t_\t_\t_\t_\t_",
                "1\tdo\tdo\tAUX\t_\t_\t_\t_\t_\t_",
                "2\tn't\tnot\tPART\t_\t_\t_\t_\t_\t_",
                "",
                "# sent_id = 2",
                "1\tRunning\trun\tVERB\t_\t_\t_\t_\t_\t_",
                "",
            ]
        )

        self.assertEqual(["do", "not", "run"], self.generator.parse_conllu_lemmas(conllu))


class FrequencyAggregationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.generator = load_generator_module()

    def test_builds_entries_from_full_wordfreq_iterator_and_udpipe_lemmas(self) -> None:
        fake_wordfreq = fake_wordfreq_module(
            words=[
                "running",
                "ran",
                "was",
                "children",
                "child",
                "_",
                "!!!",
                "bad token",
                "zero\u0000byte",
                "missing",
            ],
            frequencies={
                "running": 1e-5,
                "ran": 2e-5,
                "was": 3e-5,
                "children": 4e-6,
                "child": 6e-6,
            },
        )
        lemmatizer = FakeLemmatizer(
            {
                "running": "run",
                "ran": "run",
                "was": "be",
                "children": "child",
                "child": "child",
            }
        )

        result = self.generator.build_entries(
            language="en",
            wordlist="large",
            limit=None,
            batch_size=2,
            lemmatizer=lemmatizer,
            wordfreq_module=fake_wordfreq,
            logger=logging.getLogger("test"),
        )

        self.assertEqual([["running", "ran"], ["was", "children"], ["child"]], lemmatizer.calls)
        self.assertAlmostEqual(math.log10(3e-5) + 9.0, result.entries["run"][0])
        self.assertEqual("ran", result.entries["run"][1])
        self.assertEqual(2, result.entries["run"][2])
        self.assertAlmostEqual(math.log10(3e-5) + 9.0, result.entries["be"][0])
        self.assertEqual("was", result.entries["be"][1])
        self.assertAlmostEqual(math.log10(1e-5) + 9.0, result.entries["child"][0])
        self.assertEqual("child", result.entries["child"][1])
        self.assertEqual(2, result.entries["child"][2])
        self.assertNotIn("_", result.entries)
        self.assertNotIn("!!!", result.entries)
        self.assertEqual(10, result.stats.wordfreq_form_count)
        self.assertEqual(5, result.stats.accepted_source_form_count)
        self.assertEqual(4, result.stats.skipped_source_form_count)
        self.assertEqual(1, result.stats.skipped_missing_frequency_count)
        self.assertEqual(3, result.stats.lemma_count)

    def test_limit_applies_only_when_explicitly_supplied(self) -> None:
        fake_wordfreq = fake_wordfreq_module(
            words=["alpha", "beta", "gamma"],
            frequencies={"alpha": 1e-4, "beta": 1e-5, "gamma": 1e-6},
        )
        lemmatizer = FakeLemmatizer({"alpha": "alpha", "beta": "beta", "gamma": "gamma"})

        result = self.generator.build_entries(
            language="en",
            wordlist="large",
            limit=2,
            batch_size=10,
            lemmatizer=lemmatizer,
            wordfreq_module=fake_wordfreq,
            logger=logging.getLogger("test"),
        )

        self.assertEqual({"alpha", "beta"}, set(result.entries))
        self.assertEqual(2, result.stats.wordfreq_form_count)
        self.assertEqual([["alpha", "beta"]], lemmatizer.calls)

    def test_invalid_lemmas_are_skipped_and_counted(self) -> None:
        fake_wordfreq = fake_wordfreq_module(
            words=["good", "bad", "punct"],
            frequencies={"good": 1e-4, "bad": 1e-5, "punct": 1e-6},
        )
        lemmatizer = FakeLemmatizer({"good": "good", "bad": "_", "punct": "!!!"})

        result = self.generator.build_entries(
            language="en",
            wordlist="large",
            limit=None,
            batch_size=10,
            lemmatizer=lemmatizer,
            wordfreq_module=fake_wordfreq,
            logger=logging.getLogger("test"),
        )

        self.assertEqual(["good"], list(result.entries))
        self.assertEqual(2, result.stats.skipped_lemma_count)
        self.assertEqual(1, result.stats.lemma_count)

    def test_logs_batch_progress(self) -> None:
        fake_wordfreq = fake_wordfreq_module(
            words=["alpha", "beta"],
            frequencies={"alpha": 1e-4, "beta": 1e-5},
        )
        lemmatizer = FakeLemmatizer({"alpha": "alpha", "beta": "beta"})

        with self.assertLogs("wordfreq-builder-test", level="INFO") as captured:
            self.generator.build_entries(
                language="en",
                wordlist="large",
                limit=None,
                batch_size=1,
                lemmatizer=lemmatizer,
                wordfreq_module=fake_wordfreq,
                logger=logging.getLogger("wordfreq-builder-test"),
            )

        self.assertTrue(
            any("Processed 2 wordfreq forms" in message for message in captured.output),
            captured.output,
        )

    def test_duplicate_normalized_source_forms_are_not_double_counted(self) -> None:
        fake_wordfreq = fake_wordfreq_module(
            words=["Word", "word"],
            frequencies={"Word": 1e-4, "word": 1e-5},
        )
        lemmatizer = FakeLemmatizer({"word": "word"})

        result = self.generator.build_entries(
            language="en",
            wordlist="large",
            limit=None,
            batch_size=10,
            lemmatizer=lemmatizer,
            wordfreq_module=fake_wordfreq,
            logger=logging.getLogger("test"),
        )

        self.assertEqual(1, result.entries["word"][2])
        self.assertEqual(1, result.stats.skipped_duplicate_source_form_count)
        self.assertEqual([["word"]], lemmatizer.calls)


class WriteDatabaseTest(unittest.TestCase):
    def setUp(self) -> None:
        self.generator = load_generator_module()

    def test_writes_sqlite_database_atomically_with_version_3_metadata(self) -> None:
        entries = {
            "always": (5.76, "always", 1),
            "be": (7.07, "was", 8),
            "child": (4.0, "child", 2),
        }
        stats = self.generator.BuildStats(
            wordfreq_form_count=10,
            accepted_source_form_count=8,
            skipped_source_form_count=1,
            skipped_missing_frequency_count=1,
            skipped_duplicate_source_form_count=0,
            skipped_lemma_count=0,
            lemma_count=3,
        )

        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "nested" / "global-frequency.sqlite"
            output.parent.mkdir()
            output.write_text("not a sqlite database", encoding="utf-8")

            self.generator.write_database(
                output=output,
                language="en",
                wordlist="large",
                limit=None,
                entries=entries,
                stats=stats,
                metadata={
                    "wordfreq_package_version": "3.1.1",
                    "udpipe_package_version": "1.4.0.1",
                    "udpipe_model_sha256": "abc123",
                },
            )

            temp_files = list(output.parent.glob("*.tmp"))
            connection = sqlite3.connect(output)
            try:
                tables = {
                    row[0]
                    for row in connection.execute(
                        "SELECT name FROM sqlite_master WHERE type = 'table'",
                    )
                }
                metadata = dict(connection.execute("SELECT key, value FROM metadata"))
                integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
                rows = connection.execute(
                    """
                    SELECT language, lemma, zipf_frequency, source_form, source_form_count
                    FROM global_lemma_frequency
                    ORDER BY lemma
                    """,
                ).fetchall()
            finally:
                connection.close()

        self.assertEqual([], temp_files)
        self.assertEqual("ok", integrity)
        self.assertTrue({"metadata", "global_lemma_frequency"}.issubset(tables))
        self.assertEqual("3", metadata["database_version"])
        self.assertEqual("wordfreq", metadata["source"])
        self.assertEqual("large", metadata["wordfreq_wordlist"])
        self.assertEqual("none", metadata["word_limit"])
        self.assertEqual("udpipe", metadata["lemmatizer"])
        self.assertEqual("linear-frequency-sum-to-zipf", metadata["aggregation"])
        self.assertEqual("3.1.1", metadata["wordfreq_package_version"])
        self.assertEqual("1.4.0.1", metadata["udpipe_package_version"])
        self.assertEqual("abc123", metadata["udpipe_model_sha256"])
        self.assertEqual("10", metadata["wordfreq_form_count"])
        self.assertEqual(
            [
                ("en", "always", 5.76, "always", 1),
                ("en", "be", 7.07, "was", 8),
                ("en", "child", 4.0, "child", 2),
            ],
            rows,
        )


class MainTest(unittest.TestCase):
    def setUp(self) -> None:
        self.generator = load_generator_module()

    def test_main_creates_database_for_english(self) -> None:
        fake_wordfreq = fake_wordfreq_module(
            words=["running", "ran", "was"],
            frequencies={"running": 1e-5, "ran": 2e-5, "was": 3e-5},
        )
        lemmatizer = FakeLemmatizer({"running": "run", "ran": "run", "was": "be"})

        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "global-frequency.sqlite"
            model = Path(temp_dir) / "english-ewt.udpipe"
            model.write_bytes(b"fake model")
            expected_model_sha256 = self.generator.file_sha256(model)
            with (
                mock.patch.dict(sys.modules, {"wordfreq": fake_wordfreq}),
                mock.patch.object(self.generator, "create_lemmatizer", return_value=lemmatizer),
                mock.patch.object(self.generator, "package_version", return_value="test-version"),
                contextlib.redirect_stdout(io.StringIO()),
            ):
                result = self.generator.main(
                    [
                        "--language",
                        "en",
                        "--limit",
                        "3",
                        "--batch-size",
                        "2",
                        "--output",
                        str(output),
                        "--udpipe-model",
                        str(model),
                        "--log-level",
                        "ERROR",
                    ]
                )

            connection = sqlite3.connect(output)
            try:
                rows = connection.execute(
                    "SELECT lemma, source_form, source_form_count FROM global_lemma_frequency ORDER BY lemma",
                ).fetchall()
                metadata = dict(connection.execute("SELECT key, value FROM metadata"))
            finally:
                connection.close()

        self.assertEqual(0, result)
        self.assertEqual([("be", "was", 1), ("run", "ran", 2)], rows)
        self.assertEqual("3", metadata["database_version"])
        self.assertEqual("3", metadata["word_limit"])
        self.assertEqual(expected_model_sha256, metadata["udpipe_model_sha256"])

    def test_main_returns_error_for_missing_udpipe_dependency_or_model(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "global-frequency.sqlite"
            missing_model = Path(temp_dir) / "missing.udpipe"
            result = self.generator.main(
                [
                    "--output",
                    str(output),
                    "--udpipe-model",
                    str(missing_model),
                    "--log-level",
                    "ERROR",
                ]
            )

        self.assertEqual(2, result)

    def test_main_rejects_unsupported_languages(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "global-frequency.sqlite"
            with self.assertRaises(ValueError):
                self.generator.main(["--language", "fr", "--output", str(output)])


class OptionalUDPipeIntegrationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.generator = load_generator_module()

    def test_udpipe_lemmatizes_known_english_forms_when_available(self) -> None:
        if not has_module("ufal.udpipe") or not self.generator.DEFAULT_UDPIPE_MODEL.exists():
            self.skipTest("ufal.udpipe or the default english-ewt.udpipe model is not available")

        lemmatizer = self.generator.UDPipeLemmatizer(self.generator.DEFAULT_UDPIPE_MODEL)

        self.assertEqual(["run", "child", "be"], lemmatizer.lemmatize(["running", "children", "was"]))


if __name__ == "__main__":
    unittest.main()
