"""Backtest engine — runs a set of picks through trade-plan + exit simulation
and aggregates portfolio metrics. Uses pandas/numpy for the price-series work.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from typing import Optional

import numpy as np
import pandas as pd

from .backtest import ExitReason, metrics, simulate_exit
from .trade_plan import trade_plan


@dataclass
class PickOutcome:
    stock_code: str
    signal_date: str
    current_price: float
    feasible: bool
    entry_avg: int
    stop_loss: int
    take_profit: int
    exit_reason: Optional[str]
    exit_price: Optional[float]
    pnl_pct: Optional[float]
    holding_days: Optional[int]
    reason: Optional[str] = None

    def to_dict(self) -> dict:
        return {
            "stockCode": self.stock_code,
            "signalDate": self.signal_date,
            "currentPrice": self.current_price,
            "feasible": self.feasible,
            "entryAvg": self.entry_avg,
            "stopLoss": self.stop_loss,
            "takeProfit": self.take_profit,
            "exitReason": self.exit_reason,
            "exitPrice": self.exit_price,
            "pnlPct": self.pnl_pct,
            "holdingDays": self.holding_days,
            "reason": self.reason,
        }


def _forward_closes(series: list[dict], signal_date: str) -> tuple[list[float], int]:
    """Return closes strictly AFTER signal_date (sorted by date) and the span in
    trading rows from signal to last.
    """
    if not series:
        return [], 0
    df = pd.DataFrame(series)
    df["date"] = pd.to_datetime(df["date"])
    df = df.sort_values("date").reset_index(drop=True)
    signal_ts = pd.to_datetime(signal_date)
    forward = df[df["date"] > signal_ts]
    closes = [float(c) for c in forward["close"].to_numpy()]
    return closes, len(closes)


def run_backtest(
    entries: list[dict],
    price_series: dict[str, list[dict]],
    horizon_days: int,
    budget: Optional[float] = None,
) -> dict:
    """Run each pick through the trade plan and exit simulation, then aggregate.

    Returns a dict with ``picks`` (per-pick outcomes) and ``portfolio`` metrics.
    """
    outcomes: list[PickOutcome] = []
    trade_returns: list[float] = []
    holding_span_days = 0

    for entry in entries:
        code = entry["stockCode"]
        signal_date = entry["signalDate"]
        current_price = float(entry["currentPrice"])

        plan = trade_plan(current_price, budget)
        series = price_series.get(code, [])
        forward, span = _forward_closes(series, signal_date)

        if not plan.feasible:
            outcomes.append(
                PickOutcome(
                    stock_code=code,
                    signal_date=signal_date,
                    current_price=current_price,
                    feasible=False,
                    entry_avg=plan.avg_entry,
                    stop_loss=plan.stop_loss,
                    take_profit=plan.take_profit,
                    exit_reason=None,
                    exit_price=None,
                    pnl_pct=None,
                    holding_days=None,
                    reason=plan.reason,
                )
            )
            continue

        exit_result = simulate_exit(
            entry_avg=plan.avg_entry,
            stop=plan.stop_loss,
            take=plan.take_profit,
            forward_closes=forward,
            horizon=horizon_days,
        )
        trade_returns.append(exit_result.pnl_pct)
        holding_span_days += exit_result.holding_days

        outcomes.append(
            PickOutcome(
                stock_code=code,
                signal_date=signal_date,
                current_price=current_price,
                feasible=True,
                entry_avg=plan.avg_entry,
                stop_loss=plan.stop_loss,
                take_profit=plan.take_profit,
                exit_reason=exit_result.reason.value,
                exit_price=exit_result.exit_price,
                pnl_pct=exit_result.pnl_pct,
                holding_days=exit_result.holding_days,
            )
        )

    equity_curve = _equity_curve(trade_returns)
    port = metrics(
        returns=trade_returns,
        equity_curve=equity_curve,
        total_days=holding_span_days if holding_span_days > 0 else None,
    )

    return {
        "picks": [o.to_dict() for o in outcomes],
        "portfolio": port.to_dict(),
        "equityCurve": equity_curve,
        "horizonDays": horizon_days,
        "budget": budget,
    }


def _equity_curve(trade_returns: list[float]) -> list[float]:
    """Compound per-trade returns sequentially into an equity curve starting
    at 1.0. Each pick is treated as a sequential allocation of the full
    portfolio (a simple, transparent aggregation for the MVP).
    """
    curve = [1.0]
    for r in trade_returns:
        curve.append(curve[-1] * (1.0 + r))
    return [float(np.round(v, 8)) for v in curve]
