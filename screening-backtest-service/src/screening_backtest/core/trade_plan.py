"""Pure trade-plan domain functions.

Reimplemented faithfully from the investment-service Java domain
(`TradePlanPolicy.java`): 3 tranche entries at 100%/95%/90% of current price
with weights 30%/30%/40%, KRX tick rounding (round DOWN), stop-loss =
avg entry * 0.93, take-profit = avg entry * 1.20. Fees/taxes not modelled.

All prices are Korean won (integer). Functions are import-testable and pure.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from decimal import ROUND_FLOOR, ROUND_HALF_UP, Decimal
from typing import Optional

# KRX tick bands (2023-01 reform, KOSPI/KOSDAQ common). Ordered ascending.
# (upper_exclusive, tick). A price below `upper` uses `tick`.
_TICK_BANDS: list[tuple[Decimal, Decimal]] = [
    (Decimal(2_000), Decimal(1)),
    (Decimal(5_000), Decimal(5)),
    (Decimal(20_000), Decimal(10)),
    (Decimal(50_000), Decimal(50)),
    (Decimal(200_000), Decimal(100)),
    (Decimal(500_000), Decimal(500)),
]
_TOP_TICK = Decimal(1_000)

# Entry bands: (label, budget_share, price_ratio)
_ENTRY_BANDS: list[tuple[str, Decimal, Decimal]] = [
    ("1차", Decimal("0.30"), Decimal("1")),
    ("2차", Decimal("0.30"), Decimal("0.95")),
    ("3차", Decimal("0.40"), Decimal("0.90")),
]

STOP_LOSS_RATIO = Decimal("0.93")
TAKE_PROFIT_RATIO = Decimal("1.20")


def krx_tick_size(price: Decimal) -> Decimal:
    """Return the KRX tick size for a price (2023-01 reform table)."""
    for upper, tick in _TICK_BANDS:
        if price < upper:
            return tick
    return _TOP_TICK


def krx_tick_round_down(price) -> int:
    """Round a price DOWN to the nearest KRX tick. Returns an int (won).

    Examples: 797000 -> tick 1000 -> 797000; 49999 -> tick 50 -> 49950;
    50000 -> tick 100 -> 50000.
    """
    p = Decimal(str(price))
    tick = krx_tick_size(p)
    floored = (p / tick).to_integral_value(rounding=ROUND_FLOOR) * tick
    return int(floored.to_integral_value(rounding=ROUND_FLOOR))


@dataclass
class EntryBand:
    label: str
    price: int
    budget_share: float
    quantity: Optional[int] = None
    amount: Optional[int] = None

    def to_dict(self) -> dict:
        return {
            "label": self.label,
            "price": self.price,
            "budgetShare": self.budget_share,
            "quantity": self.quantity,
            "amount": self.amount,
        }


@dataclass
class TradePlan:
    feasible: bool
    entries: list[EntryBand] = field(default_factory=list)
    total_quantity: Optional[int] = None
    total_amount: Optional[int] = None
    avg_entry: int = 0
    stop_loss: int = 0
    take_profit: int = 0
    reason: Optional[str] = None

    def to_dict(self) -> dict:
        return {
            "feasible": self.feasible,
            "reason": self.reason,
            "entries": [e.to_dict() for e in self.entries],
            "totalQuantity": self.total_quantity,
            "totalAmount": self.total_amount,
            "avgEntry": self.avg_entry,
            "stopLoss": self.stop_loss,
            "takeProfit": self.take_profit,
        }


def _half_up_int(value: Decimal) -> int:
    return int(value.to_integral_value(rounding=ROUND_HALF_UP))


def trade_plan(current_price, budget=None) -> TradePlan:
    """Compute the 3-tranche trade plan.

    If ``budget`` is None, returns a price-levels-only plan whose avg entry is
    the weighted (30/30/40) average of the tick-rounded band prices.
    Otherwise allocates the budget across bands, floors share quantities, and
    derives avg entry from filled amounts.
    """
    current = Decimal(str(current_price))
    if current <= 0:
        raise ValueError(f"currentPrice must be positive: {current_price}")

    if budget is None:
        return _price_levels_only(current)

    budget_d = Decimal(str(budget))
    if budget_d <= 0:
        raise ValueError(f"budget must be positive: {budget}")

    entries: list[EntryBand] = []
    total_quantity = 0
    total_amount = 0
    for label, share, ratio in _ENTRY_BANDS:
        target = krx_tick_round_down(current * ratio)
        qty = int((budget_d * share) // target) if target > 0 else 0
        amount = target * qty
        entries.append(EntryBand(label, target, float(share), qty, amount))
        total_quantity += qty
        total_amount += amount

    if total_quantity == 0:
        return TradePlan(
            feasible=False,
            entries=entries,
            reason=(
                f"budget {budget_d} cannot buy even 1 share "
                f"(current {current})"
            ),
        )

    avg_entry_d = Decimal(total_amount) / Decimal(total_quantity)
    return TradePlan(
        feasible=True,
        entries=entries,
        total_quantity=total_quantity,
        total_amount=total_amount,
        avg_entry=_half_up_int(avg_entry_d),
        stop_loss=krx_tick_round_down(avg_entry_d * STOP_LOSS_RATIO),
        take_profit=krx_tick_round_down(avg_entry_d * TAKE_PROFIT_RATIO),
    )


def _price_levels_only(current: Decimal) -> TradePlan:
    entries: list[EntryBand] = []
    avg_entry_d = Decimal(0)
    for label, share, ratio in _ENTRY_BANDS:
        target = krx_tick_round_down(current * ratio)
        entries.append(EntryBand(label, target, float(share)))
        avg_entry_d += Decimal(target) * share
    return TradePlan(
        feasible=True,
        entries=entries,
        avg_entry=_half_up_int(avg_entry_d),
        stop_loss=krx_tick_round_down(avg_entry_d * STOP_LOSS_RATIO),
        take_profit=krx_tick_round_down(avg_entry_d * TAKE_PROFIT_RATIO),
    )
