"""Orchestration: turn a dated series into a dated forecast + metrics."""
from __future__ import annotations

import logging
from datetime import date, timedelta
from typing import Sequence

import numpy as np

from .metrics import in_sample_metrics
from .models import build_forecaster
from .types import ForecastPoint, ForecastResult

logger = logging.getLogger("forecast_service.service")


def _infer_step(dates: Sequence[date]) -> timedelta:
    """Infer the spacing between observations from the last two dates.

    Falls back to 1 day when there is only a single point.
    """
    if len(dates) >= 2:
        step = dates[-1] - dates[-2]
        if step.days > 0:
            return step
    return timedelta(days=1)


def _future_dates(last: date, step: timedelta, horizon: int) -> list[date]:
    return [last + step * (i + 1) for i in range(horizon)]


def run_forecast(
    dates: Sequence[date],
    values: Sequence[float],
    horizon: int,
    season_period: int | None = None,
    model: str = "auto",
    min_seasons: int = 2,
) -> ForecastResult:
    """Fit the requested model and produce a dated forecast.

    Deterministic given identical inputs. Short series fall back to
    seasonal-naive inside the model layer.
    """
    if len(dates) != len(values):
        raise ValueError("dates and values must be the same length")
    if len(values) == 0:
        raise ValueError("series must contain at least one point")
    if horizon < 1:
        raise ValueError("horizon must be >= 1")

    forecaster = build_forecaster(model, min_seasons=min_seasons)
    raw = forecaster.fit_predict(values, horizon, season_period)

    step = _infer_step(list(dates))
    future = _future_dates(dates[-1], step, horizon)

    points = [
        ForecastPoint(
            date=d,
            yhat=round(float(yh), 6),
            lower=round(float(lo), 6),
            upper=round(float(up), 6),
        )
        for d, yh, lo, up in zip(future, raw.yhat, raw.lower, raw.upper)
    ]

    # In-sample metrics: compare fitted vs actuals where both are defined.
    y = np.asarray(values, dtype=float)
    fitted = np.asarray(raw.fitted, dtype=float)
    mask = np.isfinite(fitted) & np.isfinite(y)
    if mask.any():
        metrics = in_sample_metrics(y[mask], fitted[mask])
    else:
        metrics = {"mape": float("nan"), "rmse": float("nan")}

    logger.info(
        "forecast produced",
        extra={
            "ctx_model": raw.model,
            "ctx_n": len(values),
            "ctx_horizon": horizon,
            "ctx_mape": metrics["mape"],
        },
    )
    return ForecastResult(
        model=raw.model,
        points=points,
        metrics_in_sample=metrics,
        fitted=fitted,
    )


def backtest_split(
    values: Sequence[float],
    test_size: int,
    season_period: int | None = None,
    model: str = "auto",
    min_seasons: int = 2,
) -> dict[str, float]:
    """Train/test evaluator: hold out the final ``test_size`` points, forecast
    them, and report out-of-sample MAPE/RMSE. Deterministic.
    """
    from .metrics import mape, rmse

    y = np.asarray(values, dtype=float)
    n = y.size
    if test_size < 1 or test_size >= n:
        raise ValueError("test_size must be in [1, len(series) - 1]")

    train, test = y[: n - test_size], y[n - test_size:]
    forecaster = build_forecaster(model, min_seasons=min_seasons)
    raw = forecaster.fit_predict(train, test_size, season_period)
    return {
        "mape": mape(test, raw.yhat),
        "rmse": rmse(test, raw.yhat),
        "model": raw.model,
        "train_size": int(train.size),
        "test_size": int(test.size),
    }
