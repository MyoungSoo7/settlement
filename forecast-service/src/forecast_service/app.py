"""FastAPI application wiring the forecasting service to HTTP."""
from __future__ import annotations

import logging
import math
from datetime import date

from fastapi import FastAPI, HTTPException

from .config import load_settings
from .demo_data import SEASON_PERIOD, load_demo_series
from .logging_config import configure_logging
from .schemas import (
    ForecastPointOut,
    ForecastRequest,
    ForecastResponse,
    HealthResponse,
    InSampleMetrics,
)
from .service import run_forecast

logger = logging.getLogger("forecast_service.app")


def _sanitize(x: float) -> float:
    """JSON has no NaN/Inf; coerce to 0.0 so the response stays valid."""
    return x if math.isfinite(x) else 0.0


def create_app() -> FastAPI:
    settings = load_settings()
    configure_logging(settings.log_level)

    app = FastAPI(
        title="forecast-service",
        version="0.1.0",
        description="Time-series forecasting of settlement revenue for the CEO dashboard.",
    )

    @app.get("/health", response_model=HealthResponse)
    def health() -> HealthResponse:
        return HealthResponse(status="UP")

    @app.post("/forecast", response_model=ForecastResponse)
    def forecast(req: ForecastRequest) -> ForecastResponse:
        dates = [p.date for p in req.series]
        values = [p.value for p in req.series]
        if dates != sorted(dates):
            raise HTTPException(
                status_code=422, detail="series must be sorted ascending by date"
            )
        try:
            result = run_forecast(
                dates=dates,
                values=values,
                horizon=req.horizon,
                season_period=req.seasonPeriod,
                model=req.model or "auto",
                min_seasons=settings.min_seasons_for_seasonal,
            )
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

        return _to_response(result)

    @app.get("/forecast/demo", response_model=ForecastResponse)
    def forecast_demo() -> ForecastResponse:
        series = load_demo_series()
        dates = [date.fromisoformat(p["date"]) for p in series]
        values = [float(p["value"]) for p in series]
        result = run_forecast(
            dates=dates,
            values=values,
            horizon=14,
            season_period=SEASON_PERIOD,
            model="auto",
            min_seasons=settings.min_seasons_for_seasonal,
        )
        return _to_response(result)

    return app


def _to_response(result) -> ForecastResponse:
    return ForecastResponse(
        model=result.model,
        forecast=[
            ForecastPointOut(
                date=p.date,
                yhat=_sanitize(p.yhat),
                lower=_sanitize(p.lower),
                upper=_sanitize(p.upper),
            )
            for p in result.points
        ],
        metricsInSample=InSampleMetrics(
            mape=_sanitize(result.metrics_in_sample["mape"]),
            rmse=_sanitize(result.metrics_in_sample["rmse"]),
        ),
    )


app = create_app()
