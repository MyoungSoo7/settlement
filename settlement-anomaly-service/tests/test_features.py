"""Feature engineering correctness + determinism."""
from anomaly_service.model.features import (
    FEATURE_NAMES,
    build_feature_matrix,
    compute_merchant_baselines,
    _parse_hour,
)


def _recs():
    return [
        {"id": "a", "merchantId": "M1", "amount": 100.0, "ts": "2026-07-15T08:30:00Z", "type": "SETTLEMENT"},
        {"id": "b", "merchantId": "M1", "amount": 120.0, "ts": "2026-07-15T09:00:00Z", "type": "SETTLEMENT"},
        {"id": "c", "merchantId": "M1", "amount": 110.0, "ts": "2026-07-15T10:15:00Z", "type": "SETTLEMENT"},
        {"id": "d", "merchantId": "M2", "amount": 5000.0, "ts": "2026-07-15T23:45:00Z", "type": "PAYOUT"},
    ]


def test_hour_parsing_handles_z_suffix():
    assert _parse_hour("2026-07-15T13:45:00Z") == 13
    assert _parse_hour("2026-07-15T00:05:00+00:00") == 0
    assert _parse_hour("garbage") == 0


def test_feature_matrix_columns_and_shape():
    df = build_feature_matrix(_recs())
    assert list(df.columns) == ["id"] + FEATURE_NAMES
    assert len(df) == 4


def test_feature_values_are_correct():
    df = build_feature_matrix(_recs()).set_index("id")
    # M1 mean = (100+120+110)/3 = 110
    row_a = df.loc["a"]
    assert row_a["amount"] == 100.0
    assert row_a["hour_of_day"] == 8.0
    assert row_a["merchant_freq"] == 3.0
    assert abs(row_a["amount_dev_from_mean"] - (100.0 - 110.0)) < 1e-6
    assert abs(row_a["amount_ratio_to_mean"] - (100.0 / 110.0)) < 1e-6
    # M2 single record, freq 1
    assert df.loc["d"]["merchant_freq"] == 1.0
    assert df.loc["d"]["hour_of_day"] == 23.0


def test_determinism():
    df1 = build_feature_matrix(_recs())
    df2 = build_feature_matrix(_recs())
    assert df1.equals(df2)


def test_merchant_baselines_robust_stats():
    baselines = compute_merchant_baselines(_recs())
    assert "M1" in baselines and "M2" in baselines
    m1 = baselines["M1"]
    assert m1.count == 3
    assert abs(m1.median - 110.0) < 1e-6
    assert m1.mad > 0  # spread exists


def test_empty_records():
    df = build_feature_matrix([])
    assert df.empty
