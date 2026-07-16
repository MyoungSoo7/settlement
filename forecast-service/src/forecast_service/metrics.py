"""Pure forecast-accuracy metrics. No external state, fully deterministic."""
from __future__ import annotations

import numpy as np


def _as_array(values) -> np.ndarray:
    arr = np.asarray(values, dtype=float)
    if arr.ndim != 1:
        raise ValueError("expected a 1-D sequence of numbers")
    return arr


def rmse(actual, predicted) -> float:
    """Root mean squared error."""
    a = _as_array(actual)
    p = _as_array(predicted)
    if a.shape != p.shape:
        raise ValueError("actual and predicted must have the same length")
    if a.size == 0:
        raise ValueError("cannot compute RMSE on empty arrays")
    return float(np.sqrt(np.mean((a - p) ** 2)))


def mape(actual, predicted) -> float:
    """Mean absolute percentage error, expressed as a percentage (0..inf).

    Zero-valued actuals are skipped to avoid division by zero. If every
    actual is zero, returns NaN.
    """
    a = _as_array(actual)
    p = _as_array(predicted)
    if a.shape != p.shape:
        raise ValueError("actual and predicted must have the same length")
    if a.size == 0:
        raise ValueError("cannot compute MAPE on empty arrays")
    mask = a != 0.0
    if not mask.any():
        return float("nan")
    return float(np.mean(np.abs((a[mask] - p[mask]) / a[mask])) * 100.0)


def in_sample_metrics(actual, fitted) -> dict[str, float]:
    """Convenience wrapper returning both MAPE and RMSE rounded sanely."""
    return {"mape": mape(actual, fitted), "rmse": rmse(actual, fitted)}
