"""Model-level behaviour: seasonal-naive, Holt-Winters, fallbacks."""
import numpy as np
import pytest

from forecast_service.models import (
    HoltWintersForecaster,
    SeasonalNaiveForecaster,
    build_forecaster,
)


def test_seasonal_naive_repeats_last_season():
    # Two full weeks; last season is the final 7 values.
    season = [10, 20, 30, 40, 50, 5, 2]
    series = season + season  # 14 points
    fc = SeasonalNaiveForecaster()
    raw = fc.fit_predict(series, horizon=7, season_period=7)
    # Forecast of one full horizon should exactly repeat the last season.
    assert np.allclose(raw.yhat, season)


def test_seasonal_naive_horizon_wraps_across_seasons():
    season = [1, 2, 3]
    series = season * 3  # 9 points, m=3
    raw = SeasonalNaiveForecaster().fit_predict(series, horizon=5, season_period=3)
    # positions 0,1,2,0,1 -> [1,2,3,1,2]
    assert np.allclose(raw.yhat, [1, 2, 3, 1, 2])


def test_naive_repeats_last_value_when_no_season():
    raw = SeasonalNaiveForecaster().fit_predict([5, 9, 13], horizon=3)
    assert np.allclose(raw.yhat, [13, 13, 13])


def test_holt_winters_linear_trend_direction_and_level():
    # Clean upward linear series y = 100 + 10*t, t=0..29
    t = np.arange(30)
    series = (100 + 10 * t).astype(float).tolist()
    raw = HoltWintersForecaster().fit_predict(series, horizon=3)
    # Next three should continue upward and land near 400, 410, 420.
    assert raw.yhat[0] < raw.yhat[1] < raw.yhat[2]  # increasing
    expected = np.array([400.0, 410.0, 420.0])
    assert np.allclose(raw.yhat, expected, rtol=0.05)


def test_holt_winters_seasonal_recovers_level():
    # linear trend + weekly additive seasonal, 12 weeks
    rng = np.random.default_rng(7)
    t = np.arange(84)
    weekly = np.array([0, 0, 0, 0, 0, -20, -35], dtype=float)
    series = 500 + 3 * t + weekly[t % 7] + rng.normal(0, 1, 84)
    raw = HoltWintersForecaster().fit_predict(series.tolist(), horizon=7, season_period=7)
    assert raw.model == "holt_winters_seasonal"
    # Forecast level should be near the recent series level (~ last value + trend).
    assert raw.yhat.mean() == pytest.approx(series[-7:].mean() + 3 * 7, rel=0.1)


def test_short_series_falls_back_to_naive():
    # 3 points is below the Holt-Winters trend threshold (n < 4).
    raw = HoltWintersForecaster().fit_predict([7, 8, 9], horizon=2)
    assert raw.model == "seasonal_naive"
    assert np.allclose(raw.yhat, [9, 9])


def test_seasonal_requested_but_too_short_uses_trend_only():
    # 10 points, weekly season requested -> not enough seasons -> trend only.
    series = list(range(1, 11))
    raw = HoltWintersForecaster(min_seasons=2).fit_predict(
        series, horizon=2, season_period=7
    )
    assert raw.model == "holt_winters_trend"


def test_intervals_bracket_point_forecast():
    raw = HoltWintersForecaster().fit_predict(
        (100 + 10 * np.arange(20)).tolist(), horizon=3
    )
    assert np.all(raw.lower <= raw.yhat)
    assert np.all(raw.yhat <= raw.upper)


def test_build_forecaster_names():
    assert isinstance(build_forecaster("auto"), HoltWintersForecaster)
    assert isinstance(build_forecaster("holt_winters"), HoltWintersForecaster)
    assert isinstance(build_forecaster("naive"), SeasonalNaiveForecaster)
    with pytest.raises(ValueError):
        build_forecaster("nope")


def test_determinism():
    series = (100 + 10 * np.arange(25)).tolist()
    a = HoltWintersForecaster().fit_predict(series, horizon=4)
    b = HoltWintersForecaster().fit_predict(series, horizon=4)
    assert np.allclose(a.yhat, b.yhat)
