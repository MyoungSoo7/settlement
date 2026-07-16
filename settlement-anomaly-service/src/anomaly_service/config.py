"""Runtime configuration sourced from environment variables.

All settings are read once at import time. Keep this module free of heavy
imports so it can be used by tests cheaply.
"""
from __future__ import annotations

import os
from dataclasses import dataclass


def _get_float(name: str, default: float) -> float:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        return float(raw)
    except ValueError:
        return default


def _get_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        return int(raw)
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    """Immutable service configuration."""

    port: int = _get_int("PORT", 8121)
    # Score at/above this is flagged isAnomaly=True. In [0, 1].
    anomaly_threshold: float = _get_float("ANOMALY_THRESHOLD", 0.7)
    # Seed for reproducible IsolationForest + any sampling.
    seed: int = _get_int("ANOMALY_SEED", 42)
    # IsolationForest contamination hint (expected outlier fraction).
    contamination: float = _get_float("ANOMALY_CONTAMINATION", 0.05)
    # Number of trees in the forest.
    n_estimators: int = _get_int("ANOMALY_N_ESTIMATORS", 200)
    log_level: str = os.environ.get("LOG_LEVEL", "INFO").upper()

    def __post_init__(self) -> None:
        if not 0.0 <= self.anomaly_threshold <= 1.0:
            raise ValueError("ANOMALY_THRESHOLD must be within [0, 1]")


def get_settings() -> Settings:
    """Build a fresh Settings from the current environment.

    Not cached so tests can monkeypatch env vars and rebuild.
    """
    return Settings()
