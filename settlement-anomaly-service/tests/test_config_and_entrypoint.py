"""Configuration parsing, the entrypoint, and the bundled-dataset writer.

Config parsing deserves tests precisely because it never fails loudly: a typo
in an env var silently falls back to a default, and the service then runs with
a threshold nobody chose.
"""
from __future__ import annotations

import json
import os

import pytest

from anomaly_service import __main__ as entrypoint
from anomaly_service.config import Settings, _get_float, _get_int, get_settings
from anomaly_service.sample_data import build_sample_dataset, write_sample_files


class TestNumericEnvParsing:
    def test_missing_and_blank_fall_back_to_the_default(self, monkeypatch):
        monkeypatch.delenv("X_TEST_VALUE", raising=False)
        assert _get_int("X_TEST_VALUE", 7) == 7
        assert _get_float("X_TEST_VALUE", 0.5) == 0.5

        monkeypatch.setenv("X_TEST_VALUE", "   ")
        assert _get_int("X_TEST_VALUE", 7) == 7
        assert _get_float("X_TEST_VALUE", 0.5) == 0.5

    def test_a_valid_value_wins(self, monkeypatch):
        monkeypatch.setenv("X_TEST_VALUE", "13")
        assert _get_int("X_TEST_VALUE", 7) == 13
        monkeypatch.setenv("X_TEST_VALUE", "0.25")
        assert _get_float("X_TEST_VALUE", 0.5) == 0.25

    def test_an_unparseable_value_falls_back_instead_of_crashing_the_boot(self, monkeypatch):
        # A container that refuses to start on a typo is worse than one that
        # boots on defaults — but the fallback must be the *declared* default.
        monkeypatch.setenv("X_TEST_VALUE", "seven")
        assert _get_int("X_TEST_VALUE", 7) == 7
        monkeypatch.setenv("X_TEST_VALUE", "half")
        assert _get_float("X_TEST_VALUE", 0.5) == 0.5

    def test_a_float_string_is_not_silently_truncated_to_an_int(self, monkeypatch):
        monkeypatch.setenv("X_TEST_VALUE", "8.9")
        assert _get_int("X_TEST_VALUE", 7) == 7


class TestSettings:
    def test_defaults_match_the_documented_values(self):
        settings = Settings()
        assert settings.port == 8121
        assert settings.anomaly_threshold == 0.7
        assert settings.contamination == 0.05
        assert settings.n_estimators == 200
        assert settings.seed == 42

    @pytest.mark.parametrize("threshold", [-0.1, 1.1])
    def test_a_threshold_outside_zero_to_one_is_rejected(self, threshold):
        # The score is a probability-like value; a threshold outside [0,1] means
        # either everything or nothing is an anomaly, silently.
        with pytest.raises(ValueError, match=r"\[0, 1\]"):
            Settings(anomaly_threshold=threshold)

    @pytest.mark.parametrize("threshold", [0.0, 1.0])
    def test_the_bounds_themselves_are_allowed(self, threshold):
        assert Settings(anomaly_threshold=threshold).anomaly_threshold == threshold

    def test_env_is_read_once_at_import_not_per_call(self, monkeypatch):
        # ⚠ Trap worth pinning: the dataclass field defaults are *expressions*,
        # so os.environ is consulted when the class is defined — not when
        # get_settings() runs. Setting an env var after import therefore
        # changes nothing, despite get_settings()'s "build a fresh Settings
        # from the current environment" docstring. Anything that sets env vars
        # after startup (a test, a reload hook) is silently ignored.
        before = get_settings().anomaly_threshold
        monkeypatch.setenv("ANOMALY_THRESHOLD", "0.9")

        assert get_settings().anomaly_threshold == before

    def test_explicit_construction_is_the_way_to_override(self, monkeypatch):
        # Because of the import-time read above, the supported override in
        # process is the constructor argument, which __post_init__ still guards.
        assert Settings(anomaly_threshold=0.9).anomaly_threshold == 0.9

    def test_log_level_is_upper_cased(self):
        # Whatever LOG_LEVEL held at import, the value is normalised — logging
        # config matches on upper-case names and would fall through otherwise.
        assert get_settings().log_level == get_settings().log_level.upper()


class TestEntrypoint:
    def test_main_starts_uvicorn_on_the_configured_port(self, monkeypatch):
        recorded = {}
        monkeypatch.setattr(
            entrypoint.uvicorn,
            "run",
            lambda app, host, port, log_config: recorded.update(
                app=app, host=host, port=port, log_config=log_config
            ),
        )
        entrypoint.main()

        assert recorded["app"] == "anomaly_service.api.app:app"
        assert recorded["host"] == "0.0.0.0"
        # The port comes from Settings (read at import — see the config test),
        # never hard-coded here; a divergence would make the container listen
        # somewhere the compose/k8s port mapping does not expect.
        assert recorded["port"] == get_settings().port
        # uvicorn's own log config would replace this service's JSON logging.
        assert recorded["log_config"] is None


class TestSampleFiles:
    def test_the_bundled_dataset_is_written_as_two_json_files(self, tmp_path):
        target = tmp_path / "nested" / "data"

        write_sample_files(str(target), seed=42)

        train = json.loads((target / "sample_train.json").read_text())
        demo = json.loads((target / "sample_demo.json").read_text())
        expected = build_sample_dataset(seed=42)

        assert train == expected["train"]
        assert demo["records"] == expected["demo"]
        # The ids are what a demo asserts against — without them the demo can
        # only say "something was flagged", not "the right things were".
        assert demo["outlier_ids"] == expected["outlier_ids"]
        assert demo["outlier_ids"], "the demo set must contain planted outliers"

    def test_writing_is_idempotent_for_a_seed(self, tmp_path):
        target = str(tmp_path / "data")
        write_sample_files(target, seed=7)
        first = (tmp_path / "data" / "sample_train.json").read_text()

        write_sample_files(target, seed=7)

        assert (tmp_path / "data" / "sample_train.json").read_text() == first
        assert os.path.isdir(target)
