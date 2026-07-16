"""Exit simulation and portfolio metrics — pure functions.

``simulate_exit`` walks forward daily closes from a signal date and decides the
exit: TAKE_PROFIT if the take level is reached, STOP_LOSS if the stop level is
breached, otherwise HORIZON (mark-to-market at the end of the horizon window).

``metrics`` computes portfolio risk/return stats from per-trade returns and an
equity curve: total return, CAGR, max drawdown, Sharpe (rf=0), win rate,
avg win / avg loss, num trades.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from enum import Enum
from typing import Optional, Sequence

TRADING_DAYS_PER_YEAR = 252


class ExitReason(str, Enum):
    TAKE_PROFIT = "TAKE_PROFIT"
    STOP_LOSS = "STOP_LOSS"
    HORIZON = "HORIZON"


@dataclass
class ExitResult:
    reason: ExitReason
    exit_price: float
    holding_days: int
    pnl_pct: float

    def to_dict(self) -> dict:
        return {
            "exitReason": self.reason.value,
            "exitPrice": self.exit_price,
            "holdingDays": self.holding_days,
            "pnlPct": self.pnl_pct,
        }


def simulate_exit(
    entry_avg: float,
    stop: float,
    take: float,
    forward_closes: Sequence[float],
    horizon: int,
) -> ExitResult:
    """Simulate the forward path and return the exit.

    forward_closes are the daily closes AFTER the signal date (day 1..N).
    On each day, take-profit is checked before stop-loss (both intraday on
    close). If neither triggers within ``horizon`` days, exit at the last
    observed close inside the horizon (HORIZON, mark-to-market).
    """
    if entry_avg <= 0:
        raise ValueError("entry_avg must be positive")
    if horizon <= 0:
        raise ValueError("horizon must be positive")

    window = list(forward_closes[:horizon])
    for i, close in enumerate(window, start=1):
        if close >= take:
            return ExitResult(ExitReason.TAKE_PROFIT, take, i, _pnl(entry_avg, take))
        if close <= stop:
            return ExitResult(ExitReason.STOP_LOSS, stop, i, _pnl(entry_avg, stop))

    if not window:
        # No forward data at all — flat at entry.
        return ExitResult(ExitReason.HORIZON, entry_avg, 0, 0.0)

    last_close = window[-1]
    return ExitResult(
        ExitReason.HORIZON, last_close, len(window), _pnl(entry_avg, last_close)
    )


def _pnl(entry: float, exit_price: float) -> float:
    return (exit_price - entry) / entry


@dataclass
class PortfolioMetrics:
    total_return: float
    cagr: float
    max_drawdown: float
    sharpe: float
    win_rate: float
    avg_win: float
    avg_loss: float
    num_trades: int

    def to_dict(self) -> dict:
        return {
            "totalReturn": self.total_return,
            "cagr": self.cagr,
            "maxDrawdown": self.max_drawdown,
            "sharpe": self.sharpe,
            "winRate": self.win_rate,
            "avgWin": self.avg_win,
            "avgLoss": self.avg_loss,
            "numTrades": self.num_trades,
        }


def max_drawdown(equity_curve: Sequence[float]) -> float:
    """Max drawdown as a negative fraction (e.g. -0.20 for a 20% drawdown)."""
    if not equity_curve:
        return 0.0
    peak = equity_curve[0]
    mdd = 0.0
    for value in equity_curve:
        if value > peak:
            peak = value
        if peak > 0:
            dd = (value - peak) / peak
            if dd < mdd:
                mdd = dd
    return mdd


def sharpe(returns: Sequence[float], rf: float = 0.0) -> float:
    """Sharpe ratio (rf=0 default). Uses population std of per-trade returns.

    Not annualized — this is a per-period Sharpe over the trade returns, which
    is well-defined for a discrete set of independent picks.
    """
    n = len(returns)
    if n == 0:
        return 0.0
    excess = [r - rf for r in returns]
    mean = sum(excess) / n
    var = sum((x - mean) ** 2 for x in excess) / n
    std = math.sqrt(var)
    if std == 0:
        return 0.0
    return mean / std


def metrics(
    returns: Sequence[float],
    equity_curve: Sequence[float],
    total_days: Optional[int] = None,
    rf: float = 0.0,
) -> PortfolioMetrics:
    """Compute portfolio metrics.

    ``returns`` — per-trade fractional returns (e.g. 0.12 for +12%).
    ``equity_curve`` — cumulative equity path (starts at 1.0 by convention).
    ``total_days`` — calendar span used to annualize CAGR; if None, uses the
    number of trades as periods.
    """
    num_trades = len(returns)
    if equity_curve:
        total_return = equity_curve[-1] / equity_curve[0] - 1.0
    else:
        total_return = 0.0

    # CAGR annualized over trading-day span.
    if total_days and total_days > 0 and (1.0 + total_return) > 0:
        years = total_days / TRADING_DAYS_PER_YEAR
        cagr = (1.0 + total_return) ** (1.0 / years) - 1.0 if years > 0 else 0.0
    else:
        cagr = 0.0

    wins = [r for r in returns if r > 0]
    losses = [r for r in returns if r < 0]
    win_rate = len(wins) / num_trades if num_trades else 0.0
    avg_win = sum(wins) / len(wins) if wins else 0.0
    avg_loss = sum(losses) / len(losses) if losses else 0.0

    return PortfolioMetrics(
        total_return=total_return,
        cagr=cagr,
        max_drawdown=max_drawdown(equity_curve),
        sharpe=sharpe(returns, rf=rf),
        win_rate=win_rate,
        avg_win=avg_win,
        avg_loss=avg_loss,
        num_trades=num_trades,
    )
