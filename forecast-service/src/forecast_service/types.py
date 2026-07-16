"""Shared dataclasses / protocol for the forecasting layer."""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from typing import Protocol, Sequence, runtime_checkable

import numpy as np


@dataclass(frozen=True)
class ForecastPoint:
    date: date
    yhat: float
    lower: float
    upper: float


@dataclass(frozen=True)
class ForecastResult:
    model: str
    points: list[ForecastPoint]
    metrics_in_sample: dict[str, float]
    # Values fitted to the training set (same length as input), for backtests.
    fitted: np.ndarray = field(default_factory=lambda: np.array([]))


@runtime_checkable
class Forecaster(Protocol):
    """Pluggable forecasting strategy.

    Implementations take a 1-D array of observed values and a horizon, and
    return point forecasts plus symmetric prediction intervals.
    """

    name: str

    def fit_predict(
        self,
        values: Sequence[float],
        horizon: int,
        season_period: int | None = None,
    ) -> "RawForecast":
        ...


@dataclass(frozen=True)
class RawForecast:
    """Model output before dates are attached."""

    model: str
    yhat: np.ndarray
    lower: np.ndarray
    upper: np.ndarray
    fitted: np.ndarray
