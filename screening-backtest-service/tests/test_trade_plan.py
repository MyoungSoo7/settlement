"""Tests for KRX tick rounding and the trade plan."""

from decimal import Decimal

import pytest

from screening_backtest.core.trade_plan import (
    krx_tick_round_down,
    krx_tick_size,
    trade_plan,
)


@pytest.mark.parametrize(
    "price,expected_tick",
    [
        (1999, 1),
        (2000, 5),
        (4999, 5),
        (5000, 10),
        (19999, 10),
        (20000, 50),
        (49999, 50),
        (50000, 100),
        (199999, 100),
        (200000, 500),
        (499999, 500),
        (500000, 1000),
        (797000, 1000),
    ],
)
def test_tick_size_band_boundaries(price, expected_tick):
    assert int(krx_tick_size(Decimal(price))) == expected_tick


@pytest.mark.parametrize(
    "price,expected",
    [
        (797000, 797000),   # tick 1000, already aligned
        (797999, 797000),   # tick 1000, floors down
        (49999, 49950),     # tick 50 -> 49999//50*50
        (50000, 50000),     # tick 100, aligned
        (50049, 50000),     # tick 100, floors
        (1999, 1999),       # tick 1
        (4999, 4995),       # tick 5
    ],
)
def test_tick_round_down(price, expected):
    assert krx_tick_round_down(price) == expected


def test_trade_plan_stop_lt_entry_lt_take():
    plan = trade_plan(70000, budget=10_000_000)
    assert plan.feasible
    assert plan.stop_loss < plan.avg_entry < plan.take_profit


def test_trade_plan_price_levels_only():
    plan = trade_plan(70000, budget=None)
    assert plan.feasible
    assert plan.total_quantity is None
    # bands at 100/95/90% tick-rounded of 70000
    prices = [e.price for e in plan.entries]
    assert prices == [70000, 66500, 63000]
    assert plan.stop_loss < plan.avg_entry < plan.take_profit


def test_trade_plan_infeasible_when_budget_too_small():
    plan = trade_plan(200000, budget=1000)
    assert not plan.feasible
    assert plan.reason is not None


def test_trade_plan_rejects_bad_input():
    with pytest.raises(ValueError):
        trade_plan(0, budget=1000)
    with pytest.raises(ValueError):
        trade_plan(1000, budget=-5)
