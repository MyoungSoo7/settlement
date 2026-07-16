"""FastAPI application for the screening-backtest-service."""

from __future__ import annotations

import json
import logging
from pathlib import Path

from fastapi import FastAPI

from ..config import configure_logging, settings
from ..core.engine import run_backtest
from .schemas import BacktestRequest

configure_logging(settings.log_level)
logger = logging.getLogger("screening_backtest")

DATA_DIR = Path(__file__).resolve().parents[3] / "data"
SAMPLE_FILE = DATA_DIR / "sample_backtest.json"

app = FastAPI(
    title="screening-backtest-service",
    description="Backtests the investment stock-screening trade-plan rules over "
    "historical prices and reports risk/return metrics.",
    version="0.1.0",
)


@app.get("/health")
def health() -> dict:
    return {"status": "UP"}


@app.post("/backtest")
def backtest(req: BacktestRequest) -> dict:
    logger.info(
        "backtest request: %d picks, horizon=%d, budget=%s",
        len(req.entries),
        req.horizonDays,
        req.budget,
    )
    result = run_backtest(**req.to_engine_args())
    logger.info(
        "backtest done: totalReturn=%.4f numTrades=%d",
        result["portfolio"]["totalReturn"],
        result["portfolio"]["numTrades"],
    )
    return result


@app.get("/backtest/demo")
def backtest_demo() -> dict:
    """Run the bundled sample dataset end-to-end (zero external input)."""
    payload = json.loads(SAMPLE_FILE.read_text(encoding="utf-8"))
    req = BacktestRequest(**payload)
    result = run_backtest(**req.to_engine_args())
    result["dataset"] = "data/sample_backtest.json"
    return result
