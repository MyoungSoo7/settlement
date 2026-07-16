"""Metric math on known arrays."""
import math

import pytest

from forecast_service.metrics import mape, rmse


def test_rmse_known_values():
    # errors: [1, -1, 2, -2] -> squared [1,1,4,4] mean=2.5 sqrt=1.5811...
    actual = [10, 20, 30, 40]
    pred = [11, 19, 32, 38]
    assert rmse(actual, pred) == pytest.approx(math.sqrt(2.5), rel=1e-9)


def test_rmse_perfect_is_zero():
    assert rmse([1, 2, 3], [1, 2, 3]) == 0.0


def test_mape_known_values():
    # |(-10)/100| + |10/200| = 0.10 + 0.05 => mean 0.075 => 7.5%
    actual = [100, 200]
    pred = [110, 190]
    assert mape(actual, pred) == pytest.approx(7.5, rel=1e-9)


def test_mape_skips_zero_actuals():
    # zero actual is skipped; remaining |(-10)/100| = 10%
    actual = [0, 100]
    pred = [5, 110]
    assert mape(actual, pred) == pytest.approx(10.0, rel=1e-9)


def test_mape_all_zero_actuals_is_nan():
    assert math.isnan(mape([0, 0], [1, 2]))


def test_length_mismatch_raises():
    with pytest.raises(ValueError):
        rmse([1, 2], [1])
    with pytest.raises(ValueError):
        mape([1, 2], [1])
