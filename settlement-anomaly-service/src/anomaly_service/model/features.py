"""Deterministic feature engineering.

Raw records [{id, merchantId, amount, ts, type}] are turned into a numeric
feature matrix. Every transform here is pure and order-preserving so the same
input always yields the same output (important for reproducible scoring/tests).

Features produced per record:
  - amount               : raw transaction amount
  - log_amount           : log1p(|amount|) -- compresses heavy right tail
  - hour_of_day          : 0..23 extracted from ts
  - merchant_freq        : how many records this merchant has in the batch
  - amount_dev_from_mean : amount - per-merchant mean amount (this batch)
  - amount_ratio_to_mean : amount / (per-merchant mean amount + eps)
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Dict, List, Sequence

import numpy as np
import pandas as pd

FEATURE_NAMES: List[str] = [
    "amount",
    "log_amount",
    "hour_of_day",
    "merchant_freq",
    "amount_dev_from_mean",
    "amount_ratio_to_mean",
]

_EPS = 1e-9


def _parse_hour(ts: str) -> int:
    """Extract hour-of-day from an ISO-8601 timestamp.

    Tolerant of a trailing 'Z' (UTC). Falls back to 0 on unparseable input
    rather than raising, so a single bad record cannot poison a batch.
    """
    if not ts:
        return 0
    cleaned = ts.strip()
    if cleaned.endswith("Z"):
        cleaned = cleaned[:-1] + "+00:00"
    try:
        return datetime.fromisoformat(cleaned).hour
    except ValueError:
        # Last-resort: try to grab the HH after the 'T'.
        try:
            return int(ts.split("T", 1)[1][:2])
        except (IndexError, ValueError):
            return 0


@dataclass
class MerchantBaseline:
    """Per-merchant statistics learned at train time.

    Stores median + MAD of amount (robust) and the mean (for deviation
    features), plus the observed frequency.
    """

    median: float
    mad: float
    mean: float
    count: int


def compute_merchant_baselines(
    records: Sequence[dict],
) -> Dict[str, MerchantBaseline]:
    """Compute robust per-merchant baselines from a set of records."""
    df = to_dataframe(records)
    baselines: Dict[str, MerchantBaseline] = {}
    for merchant_id, grp in df.groupby("merchantId"):
        amounts = grp["amount"].to_numpy(dtype=float)
        median = float(np.median(amounts))
        # MAD scaled to be a consistent estimator of std for normal data.
        mad = float(np.median(np.abs(amounts - median))) * 1.4826
        baselines[str(merchant_id)] = MerchantBaseline(
            median=median,
            mad=mad,
            mean=float(np.mean(amounts)),
            count=int(len(amounts)),
        )
    return baselines


def to_dataframe(records: Sequence[dict]) -> pd.DataFrame:
    """Normalise raw records into a stable, typed DataFrame."""
    rows = []
    for r in records:
        rows.append(
            {
                "id": str(r.get("id", "")),
                "merchantId": str(r.get("merchantId", "")),
                "amount": float(r.get("amount", 0.0)),
                "hour_of_day": _parse_hour(str(r.get("ts", ""))),
                "type": str(r.get("type", "SETTLEMENT") or "SETTLEMENT"),
            }
        )
    df = pd.DataFrame(
        rows,
        columns=["id", "merchantId", "amount", "hour_of_day", "type"],
    )
    return df


def build_feature_matrix(
    records: Sequence[dict],
    baselines: Dict[str, MerchantBaseline] | None = None,
) -> pd.DataFrame:
    """Turn raw records into the numeric feature DataFrame.

    If ``baselines`` is provided (learned at train time), per-merchant mean is
    taken from it; unseen merchants fall back to the batch mean. If not
    provided, per-merchant means are computed from the current batch. This keeps
    the function usable both for training (batch-internal stats) and scoring
    (train-time stats).
    """
    df = to_dataframe(records)
    if df.empty:
        return pd.DataFrame(columns=["id"] + FEATURE_NAMES)

    # Per-merchant frequency within this batch.
    freq = df.groupby("merchantId")["amount"].transform("count")

    # Per-merchant mean: from baselines if available, else batch mean.
    if baselines:
        global_mean = float(df["amount"].mean())
        merchant_mean = df["merchantId"].map(
            lambda m: baselines[m].mean if m in baselines else global_mean
        )
    else:
        merchant_mean = df.groupby("merchantId")["amount"].transform("mean")

    amount = df["amount"].astype(float)
    log_amount = np.log1p(amount.abs())
    dev = amount - merchant_mean
    ratio = amount / (merchant_mean.abs() + _EPS)

    out = pd.DataFrame(
        {
            "id": df["id"],
            "amount": amount,
            "log_amount": log_amount,
            "hour_of_day": df["hour_of_day"].astype(float),
            "merchant_freq": freq.astype(float),
            "amount_dev_from_mean": dev.astype(float),
            "amount_ratio_to_mean": ratio.astype(float),
        }
    )
    return out.reset_index(drop=True)


def feature_array(feature_df: pd.DataFrame) -> np.ndarray:
    """Extract the ordered numeric matrix (no id column) as ndarray."""
    return feature_df[FEATURE_NAMES].to_numpy(dtype=float)
