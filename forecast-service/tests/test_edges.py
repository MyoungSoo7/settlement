"""Edge and failure paths: entrypoint wiring, metric guards, model guards.

These are the paths that only run when something is already wrong. They are
exactly the ones that must not themselves be broken — a bad input should come
back as a 422 with a reason, never as a 500 or a silently wrong number.
"""
from __future__ import annotations

import json
import logging
from datetime import date, timedelta

import numpy as np
import pytest
from fastapi.testclient import TestClient

from forecast_service import main as entrypoint
from forecast_service.app import _sanitize, create_app
from forecast_service.logging_config import JsonFormatter
from forecast_service.metrics import _as_array, in_sample_metrics, mape, rmse
from forecast_service.models import HoltWintersForecaster, SeasonalNaiveForecaster
from forecast_service.service import _future_dates, _infer_step, run_forecast


class TestEntrypoint:
    def test_main_starts_uvicorn_with_the_configured_host_and_port(self, monkeypatch):
        recorded = {}
        monkeypatch.setattr(
            entrypoint.uvicorn,
            "run",
            lambda app, host, port, log_config: recorded.update(
                app=app, host=host, port=port, log_config=log_config
            ),
        )
        monkeypatch.setenv("PORT", "9122")
        monkeypatch.setenv("HOST", "127.0.0.1")

        entrypoint.main()

        assert recorded["app"] == "forecast_service.app:app"
        assert recorded["host"] == "127.0.0.1"
        assert recorded["port"] == 9122
        # log_config=None on purpose: uvicorn's default config would replace the
        # JSON logging this service installs itself.
        assert recorded["log_config"] is None


class TestMetricGuards:
    def test_a_2d_input_is_rejected(self):
        with pytest.raises(ValueError, match="1-D"):
            _as_array([[1.0, 2.0], [3.0, 4.0]])

    @pytest.mark.parametrize("fn", [rmse, mape])
    def test_mismatched_lengths_are_rejected(self, fn):
        with pytest.raises(ValueError, match="same length"):
            fn([1.0, 2.0], [1.0])

    @pytest.mark.parametrize("fn", [rmse, mape])
    def test_empty_input_is_rejected(self, fn):
        with pytest.raises(ValueError, match="empty"):
            fn([], [])

    def test_mape_skips_zero_actuals_instead_of_dividing_by_zero(self):
        # A zero actual would blow the percentage up to infinity and drag the
        # whole metric with it.
        assert mape([0.0, 100.0], [10.0, 110.0]) == pytest.approx(10.0)

    def test_mape_is_nan_when_every_actual_is_zero(self):
        assert np.isnan(mape([0.0, 0.0], [1.0, 2.0]))

    def test_in_sample_metrics_returns_both_axes(self):
        result = in_sample_metrics([100.0, 200.0], [110.0, 190.0])
        assert set(result) == {"mape", "rmse"}


class TestSeasonalNaiveGuards:
    def test_an_empty_series_is_rejected(self):
        with pytest.raises(ValueError, match="at least one point"):
            SeasonalNaiveForecaster().fit_predict([], 3, 7)

    def test_a_non_positive_horizon_is_rejected(self):
        with pytest.raises(ValueError, match="horizon"):
            SeasonalNaiveForecaster().fit_predict([1.0, 2.0], 0, 7)

    def test_the_season_cannot_look_back_further_than_the_data(self):
        # Asking for a 7-day season with 3 points must not read past the start.
        raw = SeasonalNaiveForecaster().fit_predict([1.0, 2.0, 3.0], 3, 7)
        assert list(raw.yhat) == [1.0, 2.0, 3.0]

    def test_no_season_period_repeats_the_last_value(self):
        raw = SeasonalNaiveForecaster().fit_predict([1.0, 2.0, 5.0], 3, None)
        assert list(raw.yhat) == [5.0, 5.0, 5.0]


class TestHoltWintersGuards:
    def test_a_non_positive_horizon_is_rejected(self):
        with pytest.raises(ValueError, match="horizon"):
            HoltWintersForecaster().fit_predict([1.0] * 30, 0, 7)


class TestServiceGuards:
    def _dates(self, n, step_days=1):
        return [date(2025, 1, 1) + timedelta(days=step_days * i) for i in range(n)]

    def test_mismatched_dates_and_values_are_rejected(self):
        with pytest.raises(ValueError, match="same length"):
            run_forecast(self._dates(3), [1.0, 2.0], horizon=2)

    def test_an_empty_series_is_rejected(self):
        with pytest.raises(ValueError, match="at least one point"):
            run_forecast([], [], horizon=2)

    def test_a_non_positive_horizon_is_rejected(self):
        with pytest.raises(ValueError, match="horizon"):
            run_forecast(self._dates(3), [1.0, 2.0, 3.0], horizon=0)

    def test_the_forecast_inherits_the_observed_spacing(self):
        # Weekly observations must produce weekly future dates, not daily ones.
        dates = self._dates(6, step_days=7)
        result = run_forecast(dates, [1.0, 2.0, 3.0, 4.0, 5.0, 6.0], horizon=2)

        assert [p.date for p in result.points] == [
            dates[-1] + timedelta(days=7),
            dates[-1] + timedelta(days=14),
        ]

    def test_a_single_point_defaults_to_daily_spacing(self):
        assert _infer_step([date(2025, 1, 1)]) == timedelta(days=1)

    def test_duplicate_timestamps_fall_back_to_daily_spacing(self):
        # Two identical dates give a zero step; stepping by zero would emit the
        # same future date for every horizon slot.
        assert _infer_step([date(2025, 1, 1), date(2025, 1, 1)]) == timedelta(days=1)

    def test_future_dates_start_after_the_last_observation(self):
        out = _future_dates(date(2025, 1, 10), timedelta(days=1), 3)
        assert out == [date(2025, 1, 11), date(2025, 1, 12), date(2025, 1, 13)]


class TestApiFailureSurface:
    def test_an_invalid_series_comes_back_as_422_not_500(self):
        client = TestClient(create_app())

        # One point with a season period the model cannot honour still has to
        # produce a clean answer or a clean 422 — never a stack trace.
        body = {
            "series": [{"date": "2025-01-01", "value": 100.0}],
            "horizon": 3,
            "seasonPeriod": 7,
        }
        response = client.post("/forecast", json=body)

        assert response.status_code in (200, 422), response.text
        if response.status_code == 422:
            assert response.json()["detail"]

    def test_nan_and_inf_never_reach_the_json_response(self):
        # JSON has no NaN/Inf; emitting them produces a body that strict
        # clients refuse to parse.
        assert _sanitize(float("nan")) == 0.0
        assert _sanitize(float("inf")) == 0.0
        assert _sanitize(-float("inf")) == 0.0
        assert _sanitize(12.5) == 12.5


class TestJsonLogging:
    def _record(self, **kwargs) -> logging.LogRecord:
        record = logging.LogRecord(
            name="forecast_service.test", level=logging.INFO, pathname=__file__,
            lineno=1, msg="hello", args=(), exc_info=kwargs.pop("exc_info", None),
        )
        for key, value in kwargs.items():
            setattr(record, key, value)
        return record

    def test_a_record_serializes_to_json(self):
        payload = json.loads(JsonFormatter().format(self._record()))

        assert payload["level"] == "INFO"
        assert payload["msg"] == "hello"
        assert payload["logger"] == "forecast_service.test"
        assert payload["ts"].endswith("+00:00"), "timestamps must be UTC, not the host's zone"

    def test_structured_extras_are_flattened_without_their_prefix(self):
        payload = json.loads(JsonFormatter().format(self._record(ctx_horizon=14)))
        assert payload["horizon"] == 14

    def test_an_exception_is_carried_in_the_payload(self):
        try:
            raise RuntimeError("boom")
        except RuntimeError:
            import sys

            record = self._record(exc_info=sys.exc_info())

        payload = json.loads(JsonFormatter().format(record))

        assert "RuntimeError: boom" in payload["exc"]
