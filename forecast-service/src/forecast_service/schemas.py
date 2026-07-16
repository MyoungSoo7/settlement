"""Pydantic request/response schemas for the HTTP API."""
from __future__ import annotations

from datetime import date

from pydantic import BaseModel, Field


class SeriesPoint(BaseModel):
    date: date
    value: float


class ForecastRequest(BaseModel):
    series: list[SeriesPoint] = Field(..., min_length=1)
    horizon: int = Field(..., ge=1, le=365)
    seasonPeriod: int | None = Field(default=None, ge=2, le=366)
    model: str | None = Field(default="auto")


class ForecastPointOut(BaseModel):
    date: date
    yhat: float
    lower: float
    upper: float


class InSampleMetrics(BaseModel):
    mape: float
    rmse: float


class ForecastResponse(BaseModel):
    model: str
    forecast: list[ForecastPointOut]
    metricsInSample: InSampleMetrics


class HealthResponse(BaseModel):
    status: str
