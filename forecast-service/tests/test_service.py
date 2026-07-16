"""Service-layer orchestration: dates, metrics, backtest."""
from datetime import date, timedelta

import numpy as np
import pytest

from forecast_service.service import backtest_split, run_forecast


def _dates(n, start=date(2025, 1, 1)):
    return [start + timedelta(days=i) for i in range(n)]


def test_run_forecast_attaches_future_dates():
    n = 30
    dates = _dates(n)
    values = (100 + 10 * np.arange(n)).astype(float).tolist()
    result = run_forecast(dates, values, horizon=3)
    assert len(result.points) == 3
    # Future dates continue daily from the last observed date.
    assert result.points[0].date == dates[-1] + timedelta(days=1)
    assert result.points[2].date == dates[-1] + timedelta(days=3)
    assert result.metrics_in_sample["rmse"] >= 0


def test_run_forecast_mismatched_lengths_raises():
    with pytest.raises(ValueError):
        run_forecast(_dates(3), [1.0, 2.0], horizon=1)


def test_backtest_split_out_of_sample():
    n = 40
    values = (100 + 5 * np.arange(n)).astype(float).tolist()
    res = backtest_split(values, test_size=5)
    assert res["train_size"] == 35
    assert res["test_size"] == 5
    # A clean linear trend should backtest with modest error.
    assert res["mape"] < 5.0


def test_backtest_invalid_size():
    with pytest.raises(ValueError):
        backtest_split([1, 2, 3], test_size=3)
