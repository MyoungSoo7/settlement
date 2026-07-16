"""Tests for exit simulation and portfolio metrics."""

import math

import pytest

from screening_backtest.core.backtest import (
    ExitReason,
    max_drawdown,
    metrics,
    sharpe,
    simulate_exit,
)


def test_simulate_exit_take_profit():
    # entry 100, take 120, stop 93. Path reaches 121 on day 3.
    r = simulate_exit(100, 93, 120, [105, 110, 121, 130], horizon=10)
    assert r.reason is ExitReason.TAKE_PROFIT
    assert r.holding_days == 3
    assert r.exit_price == 120
    assert math.isclose(r.pnl_pct, 0.20)


def test_simulate_exit_stop_loss():
    # entry 100, stop 93. Path drops to 90 on day 2.
    r = simulate_exit(100, 93, 120, [96, 90, 95], horizon=10)
    assert r.reason is ExitReason.STOP_LOSS
    assert r.holding_days == 2
    assert r.exit_price == 93
    assert math.isclose(r.pnl_pct, -0.07)


def test_simulate_exit_horizon():
    # Never hits either level within horizon; mark-to-market at last close.
    r = simulate_exit(100, 93, 120, [101, 102, 103, 104, 105], horizon=3)
    assert r.reason is ExitReason.HORIZON
    assert r.holding_days == 3
    assert r.exit_price == 103
    assert math.isclose(r.pnl_pct, 0.03)


def test_simulate_exit_take_before_stop_same_day_priority():
    # A day that is both >= take and <= stop is impossible for valid levels,
    # but confirm take is checked first when both future days exist.
    r = simulate_exit(100, 93, 120, [121, 90], horizon=10)
    assert r.reason is ExitReason.TAKE_PROFIT


def test_max_drawdown_known_curve():
    # 1.0 -> 1.2 -> 1.08 -> 1.134 : max dd = (1.08-1.2)/1.2 = -0.10
    curve = [1.0, 1.2, 1.08, 1.134]
    assert math.isclose(max_drawdown(curve), -0.10, abs_tol=1e-9)


def test_sharpe_known_returns():
    returns = [0.20, -0.10, 0.05]
    # mean 0.05, pop std sqrt(0.015) -> sharpe 0.05/0.1224745 = 0.4082483
    assert math.isclose(sharpe(returns), 0.40824829046, rel_tol=1e-9)


def test_metrics_on_known_equity_curve():
    returns = [0.20, -0.10, 0.05]
    curve = [1.0, 1.2, 1.08, 1.134]
    m = metrics(returns, curve)
    assert math.isclose(m.total_return, 0.134, abs_tol=1e-9)
    assert math.isclose(m.max_drawdown, -0.10, abs_tol=1e-9)
    assert math.isclose(m.sharpe, 0.40824829046, rel_tol=1e-9)
    assert math.isclose(m.win_rate, 2 / 3, rel_tol=1e-9)
    assert math.isclose(m.avg_win, 0.125, abs_tol=1e-9)
    assert math.isclose(m.avg_loss, -0.10, abs_tol=1e-9)
    assert m.num_trades == 3


def test_metrics_empty():
    m = metrics([], [])
    assert m.num_trades == 0
    assert m.total_return == 0.0
    assert m.sharpe == 0.0


def test_simulate_exit_rejects_bad_input():
    with pytest.raises(ValueError):
        simulate_exit(0, 93, 120, [100], horizon=5)
    with pytest.raises(ValueError):
        simulate_exit(100, 93, 120, [100], horizon=0)
