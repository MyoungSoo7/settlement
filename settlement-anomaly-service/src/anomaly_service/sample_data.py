"""Bundled sample dataset generator.

Produces a deterministic (seeded) mix of *normal* settlement/payout records
plus a handful of *injected outliers* so the model can be fit on startup and the
/score/demo endpoint has something meaningful to run. Real deployments would
replace this with a feature store / historical settlement pull.
"""
from __future__ import annotations

import json
import os
from typing import Dict, List

import numpy as np

# Each merchant has a typical amount band and a normal operating hour window.
_MERCHANTS = {
    "M-COFFEE": {"mean": 4500.0, "std": 800.0, "hours": (7, 20)},
    "M-GROCER": {"mean": 32000.0, "std": 6000.0, "hours": (9, 22)},
    "M-ELECTRO": {"mean": 250000.0, "std": 40000.0, "hours": (10, 21)},
    "M-BOOKS": {"mean": 18000.0, "std": 3000.0, "hours": (8, 23)},
    "M-TRAVEL": {"mean": 480000.0, "std": 90000.0, "hours": (6, 23)},
}


def generate_normal_records(n_per_merchant: int, seed: int) -> List[Dict]:
    rng = np.random.default_rng(seed)
    records: List[Dict] = []
    idx = 0
    for merchant, cfg in _MERCHANTS.items():
        for _ in range(n_per_merchant):
            amount = float(max(1.0, rng.normal(cfg["mean"], cfg["std"])))
            lo, hi = cfg["hours"]
            hour = int(rng.integers(lo, hi))
            minute = int(rng.integers(0, 60))
            records.append(
                {
                    "id": f"n-{idx:05d}",
                    "merchantId": merchant,
                    "amount": round(amount, 2),
                    "ts": f"2026-07-15T{hour:02d}:{minute:02d}:00Z",
                    "type": "SETTLEMENT",
                }
            )
            idx += 1
    return records


def injected_outliers() -> List[Dict]:
    """A fixed set of obviously anomalous records for demos/tests."""
    return [
        # 40x the coffee shop's typical amount.
        {
            "id": "out-amount-spike",
            "merchantId": "M-COFFEE",
            "amount": 180000.0,
            "ts": "2026-07-15T14:03:00Z",
            "type": "PAYOUT",
        },
        # Grocer payout at 3am (off hours) and much larger than normal.
        {
            "id": "out-offhours-big",
            "merchantId": "M-GROCER",
            "amount": 210000.0,
            "ts": "2026-07-15T03:12:00Z",
            "type": "PAYOUT",
        },
        # Electronics store: tiny/negative amount (refund-like anomaly).
        {
            "id": "out-negative",
            "merchantId": "M-ELECTRO",
            "amount": -500000.0,
            "ts": "2026-07-15T02:40:00Z",
            "type": "PAYOUT",
        },
        # Brand-new-looking merchant, huge single amount at odd hour.
        {
            "id": "out-unknown-merchant",
            "merchantId": "M-UNKNOWN",
            "amount": 9_500_000.0,
            "ts": "2026-07-15T04:01:00Z",
            "type": "PAYOUT",
        },
    ]


def build_sample_dataset(seed: int = 42, n_per_merchant: int = 60) -> Dict:
    """Return {'train': [...], 'demo': [...normal+outliers...]}."""
    normal = generate_normal_records(n_per_merchant, seed)
    outliers = injected_outliers()
    # A representative slice of normals for the demo, plus the outliers.
    demo_normals = normal[::20][:8]
    return {
        "train": normal,
        "demo": demo_normals + outliers,
        "outlier_ids": [o["id"] for o in outliers],
    }


def write_sample_files(data_dir: str, seed: int = 42) -> None:
    """Persist the bundled dataset as JSON files under ``data_dir``."""
    os.makedirs(data_dir, exist_ok=True)
    ds = build_sample_dataset(seed=seed)
    with open(os.path.join(data_dir, "sample_train.json"), "w") as f:
        json.dump(ds["train"], f, indent=2)
    with open(os.path.join(data_dir, "sample_demo.json"), "w") as f:
        json.dump(
            {"records": ds["demo"], "outlier_ids": ds["outlier_ids"]},
            f,
            indent=2,
        )
