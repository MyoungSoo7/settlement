"""Forecasting models: seasonal-naive baseline + Holt-Winters.

All models implement the ``Forecaster`` protocol (see types.py). They are
pure functions of their input series (deterministic) and return a
``RawForecast`` with point forecasts and symmetric +/-1.96*sigma intervals.
"""
from __future__ import annotations

import logging
import warnings
from typing import Sequence

import numpy as np

from .types import Forecaster, RawForecast

logger = logging.getLogger("forecast_service.models")

_Z_95 = 1.959963984540054  # two-sided 95% normal quantile


def _interval(yhat: np.ndarray, resid_std: float) -> tuple[np.ndarray, np.ndarray]:
    """Symmetric prediction interval around point forecasts."""
    if not np.isfinite(resid_std) or resid_std <= 0:
        resid_std = 0.0
    delta = _Z_95 * resid_std
    return yhat - delta, yhat + delta


class SeasonalNaiveForecaster:
    """Repeats the last observed season (or the last value if no season).

    For horizon h and season period m, forecast[t] = value[N - m + (t mod m)].
    With m == 1 (or unset) this degrades to the classic naive "repeat last
    value" forecaster.
    """

    name = "seasonal_naive"

    def fit_predict(
        self,
        values: Sequence[float],
        horizon: int,
        season_period: int | None = None,
    ) -> RawForecast:
        y = np.asarray(values, dtype=float)
        n = y.size
        if n == 0:
            raise ValueError("series must contain at least one point")
        if horizon < 1:
            raise ValueError("horizon must be >= 1")

        m = season_period if season_period and season_period >= 1 else 1
        m = min(m, n)  # cannot look back further than we have data

        last_season = y[n - m:]
        yhat = np.array([last_season[t % m] for t in range(horizon)], dtype=float)

        # In-sample "fitted" = value m steps earlier; first m are NaN-free by
        # falling back to the series mean so metrics stay well defined.
        fitted = np.empty(n, dtype=float)
        for t in range(n):
            fitted[t] = y[t - m] if t >= m else y[t]
        resid = y[m:] - fitted[m:] if n > m else np.array([0.0])
        lower, upper = _interval(yhat, float(np.std(resid)) if resid.size else 0.0)
        return RawForecast(self.name, yhat, lower, upper, fitted)


class HoltWintersForecaster:
    """Holt-Winters exponential smoothing (statsmodels).

    Uses additive trend, and additive seasonality when the series is long
    enough (>= min_seasons full cycles). Falls back to trend-only when there
    is a trend but not enough seasons, and ultimately delegates to
    SeasonalNaive for very short series.
    """

    name = "holt_winters"

    def __init__(self, min_seasons: int = 2) -> None:
        self.min_seasons = min_seasons

    def fit_predict(
        self,
        values: Sequence[float],
        horizon: int,
        season_period: int | None = None,
    ) -> RawForecast:
        # Imported lazily so the module imports even if statsmodels is missing
        # at collection time; the endpoint surfaces a clean error otherwise.
        from statsmodels.tsa.holtwinters import ExponentialSmoothing

        y = np.asarray(values, dtype=float)
        n = y.size
        if horizon < 1:
            raise ValueError("horizon must be >= 1")

        seasonal = None
        seasonal_periods = None
        m = season_period if season_period and season_period >= 2 else None

        # Need at least (min_seasons * m + 1) points for seasonal HW to be
        # meaningful; statsmodels itself requires > 2*m.
        if m is not None and n >= max(self.min_seasons * m + 1, 2 * m + 1):
            seasonal = "add"
            seasonal_periods = m

        # Trend requires a handful of points; below that fall back to naive.
        if n < 4:
            logger.info(
                "series too short for Holt-Winters; falling back to seasonal-naive",
                extra={"ctx_n": n},
            )
            return SeasonalNaiveForecaster().fit_predict(y, horizon, season_period)

        try:
            with warnings.catch_warnings():
                warnings.simplefilter("ignore")
                model = ExponentialSmoothing(
                    y,
                    trend="add",
                    seasonal=seasonal,
                    seasonal_periods=seasonal_periods,
                    initialization_method="estimated",
                )
                fit = model.fit(optimized=True)
            yhat = np.asarray(fit.forecast(horizon), dtype=float)
            fitted = np.asarray(fit.fittedvalues, dtype=float)
        except Exception as exc:  # pragma: no cover - defensive
            logger.warning(
                "Holt-Winters fit failed; falling back to seasonal-naive",
                extra={"ctx_error": str(exc)},
            )
            return SeasonalNaiveForecaster().fit_predict(y, horizon, season_period)

        resid = y - fitted
        resid_std = float(np.std(resid[np.isfinite(resid)])) if resid.size else 0.0
        lower, upper = _interval(yhat, resid_std)
        label = "holt_winters_seasonal" if seasonal else "holt_winters_trend"
        return RawForecast(label, yhat, lower, upper, fitted)


_REGISTRY: dict[str, Forecaster] = {}


def build_forecaster(model: str, min_seasons: int = 2) -> Forecaster:
    """Factory: return a forecaster instance by name.

    Known names: 'seasonal_naive', 'holt_winters', and 'auto' (= holt_winters
    with graceful fallback).
    """
    key = (model or "auto").lower()
    if key in ("auto", "holt_winters", "holtwinters", "hw"):
        return HoltWintersForecaster(min_seasons=min_seasons)
    if key in ("seasonal_naive", "naive", "snaive"):
        return SeasonalNaiveForecaster()
    raise ValueError(f"unknown model '{model}'")
