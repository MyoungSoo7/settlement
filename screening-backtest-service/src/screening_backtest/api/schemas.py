"""Pydantic request/response schemas for the backtest API."""

from __future__ import annotations

from typing import Optional

from pydantic import BaseModel, Field


class EntryIn(BaseModel):
    stockCode: str
    signalDate: str = Field(..., description="YYYY-MM-DD signal date")
    currentPrice: float = Field(..., gt=0)


class PricePoint(BaseModel):
    date: str
    close: float = Field(..., gt=0)


class BacktestRequest(BaseModel):
    entries: list[EntryIn]
    priceSeries: dict[str, list[PricePoint]]
    horizonDays: int = Field(..., gt=0)
    budget: Optional[float] = Field(default=None, gt=0)

    def to_engine_args(self) -> dict:
        return {
            "entries": [e.model_dump() for e in self.entries],
            "price_series": {
                code: [p.model_dump() for p in pts]
                for code, pts in self.priceSeries.items()
            },
            "horizon_days": self.horizonDays,
            "budget": self.budget,
        }
