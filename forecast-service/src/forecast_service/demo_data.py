"""Synthetic settlement-revenue series for the /forecast/demo endpoint.

Deterministic: a fixed RNG seed produces the same series every call, so the
demo forecast is reproducible.
"""
from __future__ import annotations

import csv
import json
from datetime import date, timedelta
from pathlib import Path

import numpy as np

_DATA_DIR = Path(__file__).resolve().parent.parent.parent / "data"
_DEMO_CSV = _DATA_DIR / "demo_settlement_daily.csv"

# Weekly seasonality: settlement volume dips on weekends.
SEASON_PERIOD = 7


def generate_series(
    n: int = 120,
    start: date = date(2025, 1, 1),
    seed: int = 42,
) -> list[tuple[date, float]]:
    """Trend + weekly seasonal + small noise, floored at 0."""
    rng = np.random.default_rng(seed)
    t = np.arange(n)
    base = 100_000.0
    trend = 1_500.0 * t  # steady growth
    weekly = np.array([0, 0, 0, 0, 0, -25_000, -40_000], dtype=float)
    seasonal = weekly[t % SEASON_PERIOD]
    noise = rng.normal(0, 3_000, size=n)
    values = np.maximum(base + trend + seasonal + noise, 0.0)
    dates = [start + timedelta(days=int(i)) for i in t]
    return list(zip(dates, values.round(2).tolist()))


def write_demo_csv(path: Path = _DEMO_CSV) -> Path:
    """Materialize the demo series to data/ (used at build time)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    rows = generate_series()
    with path.open("w", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(["date", "value"])
        for d, v in rows:
            writer.writerow([d.isoformat(), v])
    return path


def load_demo_series() -> list[dict]:
    """Load the bundled demo series (from CSV if present, else generate)."""
    if _DEMO_CSV.exists():
        out: list[dict] = []
        with _DEMO_CSV.open() as fh:
            reader = csv.DictReader(fh)
            for row in reader:
                out.append({"date": row["date"], "value": float(row["value"])})
        return out
    return [{"date": d.isoformat(), "value": v} for d, v in generate_series()]


if __name__ == "__main__":  # pragma: no cover
    p = write_demo_csv()
    print(json.dumps({"wrote": str(p), "rows": len(load_demo_series())}))
