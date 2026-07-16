"""Detector: z-score/MAD, IsolationForest, ensemble range."""
import pytest

from anomaly_service.model.detector import EnsembleDetector
from anomaly_service.sample_data import build_sample_dataset


@pytest.fixture(scope="module")
def fitted_detector():
    ds = build_sample_dataset(seed=42)
    det = EnsembleDetector(seed=42, threshold=0.7)
    det.fit(ds["train"])
    return det, ds


def test_zscore_mad_flags_obvious_amount_outlier(fitted_detector):
    det, _ = fitted_detector
    # M-COFFEE typical ~4500; 180000 is a huge robust-z outlier.
    outlier = {
        "id": "spike",
        "merchantId": "M-COFFEE",
        "amount": 180000.0,
        "ts": "2026-07-15T14:00:00Z",
        "type": "PAYOUT",
    }
    normal = {
        "id": "ok",
        "merchantId": "M-COFFEE",
        "amount": 4600.0,
        "ts": "2026-07-15T10:00:00Z",
        "type": "SETTLEMENT",
    }
    res = {r["id"]: r for r in det.score([outlier, normal])}
    assert res["spike"]["isAnomaly"] is True
    assert res["spike"]["anomalyScore"] > res["ok"]["anomalyScore"]
    # A z-score reason must have fired.
    assert any("z-score" in reason for reason in res["spike"]["reasons"])
    assert res["ok"]["isAnomaly"] is False


def test_isolation_forest_flags_injected_outliers_seeded(fitted_detector):
    det, ds = fitted_detector
    res = {r["id"]: r for r in det.score(ds["demo"])}
    for oid in ds["outlier_ids"]:
        assert res[oid]["isAnomaly"] is True, f"{oid} should be flagged"


def test_normal_points_mostly_pass(fitted_detector):
    det, ds = fitted_detector
    # Score a fresh batch of normal-looking coffee records.
    normals = [
        {
            "id": f"norm-{i}",
            "merchantId": "M-GROCER",
            "amount": 32000.0 + i * 100,
            "ts": "2026-07-15T12:00:00Z",
            "type": "SETTLEMENT",
        }
        for i in range(10)
    ]
    res = det.score(normals)
    flagged = sum(r["isAnomaly"] for r in res)
    assert flagged == 0, f"normals should not flag, got {flagged}"


def test_ensemble_score_within_unit_interval(fitted_detector):
    det, ds = fitted_detector
    res = det.score(ds["demo"])
    for r in res:
        assert 0.0 <= r["anomalyScore"] <= 1.0


def test_reproducible_scores(fitted_detector):
    det, ds = fitted_detector
    r1 = det.score(ds["demo"])
    ds2 = build_sample_dataset(seed=42)
    det2 = EnsembleDetector(seed=42, threshold=0.7).fit(ds2["train"])
    r2 = det2.score(ds2["demo"])
    s1 = [r["anomalyScore"] for r in r1]
    s2 = [r["anomalyScore"] for r in r2]
    assert s1 == s2


def test_fit_empty_raises():
    with pytest.raises(ValueError):
        EnsembleDetector().fit([])


def test_score_before_fit_raises():
    with pytest.raises(RuntimeError):
        EnsembleDetector().score([{"id": "x", "merchantId": "M", "amount": 1, "ts": "2026-07-15T00:00:00Z"}])
