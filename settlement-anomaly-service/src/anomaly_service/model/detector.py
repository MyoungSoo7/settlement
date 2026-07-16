"""Ensemble anomaly detector.

Combines two complementary signals into a single anomalyScore in [0, 1]:

  1. Robust statistical layer (z-score via MAD). For each record we compute how
     far its amount deviates from the *per-merchant* robust centre (median),
     scaled by the merchant's MAD. Also flags extreme hour-of-day and extreme
     amount-vs-merchant-mean ratio. This layer is interpretable and drives the
     human-readable ``reasons``.

  2. IsolationForest (sklearn). An unsupervised model trained on the full
     feature matrix. It catches multivariate outliers the univariate rules miss
     (e.g. an unusual *combination* of amount + hour + frequency).

The two scores are each mapped to [0, 1] and blended (weighted max-ish average),
so a record is anomalous if *either* layer is confident, but agreement pushes it
higher. Everything is a pure function of (record, fitted state) so it is fully
testable and reproducible under a fixed random_state.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional

import numpy as np
from sklearn.ensemble import IsolationForest

from .features import (
    FEATURE_NAMES,
    MerchantBaseline,
    build_feature_matrix,
    compute_merchant_baselines,
    feature_array,
)

# Thresholds for the statistical layer (robust z on merchant amount).
_Z_WARN = 3.5      # |z| beyond this starts contributing
_Z_STRONG = 6.0    # |z| at/above this saturates the stat score to ~1
# Hour-of-day considered "off hours" for settlement/payout activity.
_OFF_HOURS = set(range(0, 5))  # 00:00 - 04:59
# Amount/merchant-mean ratio considered extreme.
_RATIO_HIGH = 5.0

# Blend weights: stat layer vs isolation-forest layer.
_W_STAT = 0.55
_W_IFOREST = 0.45


def _sigmoid(x: float) -> float:
    return 1.0 / (1.0 + np.exp(-x))


@dataclass
class DetectorState:
    """Everything learned at fit() time."""

    baselines: Dict[str, MerchantBaseline] = field(default_factory=dict)
    global_median: float = 0.0
    global_mad: float = 1.0
    iforest: Optional[IsolationForest] = None
    # Calibration bounds for iforest decision_function (min/max on train set),
    # used to normalise raw scores into [0, 1].
    if_score_min: float = -1.0
    if_score_max: float = 1.0
    fitted: bool = False


class EnsembleDetector:
    """Stateful wrapper: fit() then score()."""

    def __init__(
        self,
        seed: int = 42,
        contamination: float = 0.05,
        n_estimators: int = 200,
        threshold: float = 0.7,
    ) -> None:
        self.seed = seed
        self.contamination = contamination
        self.n_estimators = n_estimators
        self.threshold = threshold
        self.state = DetectorState()

    # ------------------------------------------------------------------ fit
    def fit(self, records: List[dict]) -> "EnsembleDetector":
        if not records:
            raise ValueError("Cannot fit on an empty record set")

        baselines = compute_merchant_baselines(records)
        feat_df = build_feature_matrix(records, baselines=baselines)
        X = feature_array(feat_df)

        amounts = X[:, FEATURE_NAMES.index("amount")]
        global_median = float(np.median(amounts))
        global_mad = float(np.median(np.abs(amounts - global_median))) * 1.4826
        if global_mad <= 0:
            global_mad = 1.0

        iforest = IsolationForest(
            n_estimators=self.n_estimators,
            contamination=self.contamination,
            random_state=self.seed,
            n_jobs=1,
        )
        iforest.fit(X)

        # decision_function: higher = more normal. We invert later.
        raw = iforest.decision_function(X)
        self.state = DetectorState(
            baselines=baselines,
            global_median=global_median,
            global_mad=global_mad,
            iforest=iforest,
            if_score_min=float(raw.min()),
            if_score_max=float(raw.max()),
            fitted=True,
        )
        return self

    # ---------------------------------------------------------------- score
    def _stat_score_and_reasons(
        self,
        feat_row: Dict[str, float],
        merchant_id: str,
    ) -> tuple[float, List[str]]:
        """Univariate robust checks -> (score in [0,1], reasons)."""
        reasons: List[str] = []
        st = self.state

        # Robust z on amount vs merchant baseline (fallback to global).
        base = st.baselines.get(merchant_id)
        if base is not None and base.mad > 0 and base.count >= 3:
            centre, scale = base.median, base.mad
            scope = "merchant"
        else:
            centre, scale = st.global_median, st.global_mad
            scope = "global"
        z = abs(feat_row["amount"] - centre) / (scale + 1e-9)

        # Map |z| -> [0,1]: 0 below warn, ramps to ~1 at strong.
        if z >= _Z_WARN:
            z_norm = (z - _Z_WARN) / (_Z_STRONG - _Z_WARN)
            z_component = float(min(1.0, max(0.0, z_norm)))
            reasons.append(
                f"amount robust z-score {z:.1f} vs {scope} baseline "
                f"(median={centre:.1f})"
            )
        else:
            z_component = 0.0

        # Off-hours activity.
        hour = int(feat_row["hour_of_day"])
        hour_component = 0.0
        if hour in _OFF_HOURS:
            hour_component = 0.4
            reasons.append(f"off-hours activity at {hour:02d}:00")

        # Extreme amount vs merchant mean ratio.
        ratio = feat_row["amount_ratio_to_mean"]
        ratio_component = 0.0
        if ratio >= _RATIO_HIGH:
            ratio_component = float(min(1.0, (ratio - _RATIO_HIGH) / 20.0 + 0.3))
            reasons.append(
                f"amount {ratio:.1f}x the merchant mean"
            )

        # Combine univariate components: dominated by the strongest signal but
        # additional signals nudge it up.
        stat_score = max(z_component, hour_component, ratio_component)
        # small boost if multiple fired
        fired = sum(c > 0 for c in (z_component, hour_component, ratio_component))
        if fired >= 2:
            stat_score = min(1.0, stat_score + 0.15)

        return stat_score, reasons

    def score(self, records: List[dict]) -> List[dict]:
        """Score records. Returns list of {id, anomalyScore, isAnomaly, reasons}."""
        if not self.state.fitted or self.state.iforest is None:
            raise RuntimeError("Detector is not fitted; call fit() first")
        if not records:
            return []

        feat_df = build_feature_matrix(records, baselines=self.state.baselines)
        X = feature_array(feat_df)

        # IsolationForest: decision_function high=normal. Invert + normalise.
        raw = self.state.iforest.decision_function(X)
        lo, hi = self.state.if_score_min, self.state.if_score_max
        span = (hi - lo) if (hi - lo) > 1e-9 else 1.0
        results: List[dict] = []

        for i, rec in enumerate(records):
            row = {name: float(feat_df.iloc[i][name]) for name in FEATURE_NAMES}
            merchant_id = str(rec.get("merchantId", ""))

            stat_score, reasons = self._stat_score_and_reasons(row, merchant_id)

            # Normalise iforest: 0 (most normal) .. 1 (most anomalous).
            norm = (raw[i] - lo) / span          # 0=anomalous end..1=normal end
            if_anomaly = float(min(1.0, max(0.0, 1.0 - norm)))
            # sharpen with the model's own outlier prediction
            if self.state.iforest.predict(X[i : i + 1])[0] == -1:
                if_anomaly = max(if_anomaly, 0.6)
                reasons.append("isolation-forest flagged multivariate outlier")

            ensemble = _W_STAT * stat_score + _W_IFOREST * if_anomaly
            # Agreement bonus: if both layers are confident, push higher.
            if stat_score >= 0.5 and if_anomaly >= 0.5:
                ensemble = min(1.0, ensemble + 0.1)
            ensemble = float(min(1.0, max(0.0, ensemble)))

            is_anom = ensemble >= self.threshold
            if is_anom and not reasons:
                reasons.append("ensemble score above threshold")

            results.append(
                {
                    "id": str(rec.get("id", "")),
                    "anomalyScore": round(ensemble, 4),
                    "isAnomaly": bool(is_anom),
                    "reasons": reasons,
                }
            )
        return results
