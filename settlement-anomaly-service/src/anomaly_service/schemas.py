"""Pydantic request/response models for the API."""
from __future__ import annotations

from typing import List, Optional

from pydantic import BaseModel, Field


class SettlementRecord(BaseModel):
    """A raw settlement / payout record to be scored."""

    id: str = Field(..., description="Unique record id")
    merchantId: str = Field(..., description="Merchant identifier")
    amount: float = Field(..., description="Transaction amount (settlement/payout)")
    ts: str = Field(
        ...,
        description="ISO-8601 timestamp, e.g. 2026-07-16T13:45:00Z",
    )
    type: Optional[str] = Field(
        default="SETTLEMENT",
        description="Record type, e.g. SETTLEMENT or PAYOUT",
    )


class ScoreRequest(BaseModel):
    records: List[SettlementRecord] = Field(default_factory=list)


class TrainRequest(BaseModel):
    """Optional training payload. If omitted, the bundled sample is used."""

    records: Optional[List[SettlementRecord]] = None


class RecordScore(BaseModel):
    id: str
    anomalyScore: float = Field(..., ge=0.0, le=1.0)
    isAnomaly: bool
    reasons: List[str]


class ScoreResponse(BaseModel):
    threshold: float
    results: List[RecordScore]


class TrainResponse(BaseModel):
    status: str
    trainedRecords: int
    merchants: int
    threshold: float
    seed: int
