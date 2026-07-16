"""HTTP endpoint tests via FastAPI TestClient."""
from datetime import date, timedelta

import numpy as np
from fastapi.testclient import TestClient

from forecast_service.app import create_app

client = TestClient(create_app())


def test_health():
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "UP"}


def _series(n):
    start = date(2025, 1, 1)
    vals = (100 + 10 * np.arange(n)).astype(float)
    return [
        {"date": (start + timedelta(days=i)).isoformat(), "value": float(vals[i])}
        for i in range(n)
    ]


def test_forecast_endpoint_happy_path():
    body = {"series": _series(30), "horizon": 5, "model": "auto"}
    r = client.post("/forecast", json=body)
    assert r.status_code == 200, r.text
    data = r.json()
    assert data["model"].startswith("holt_winters")
    assert len(data["forecast"]) == 5
    p0 = data["forecast"][0]
    assert set(p0) == {"date", "yhat", "lower", "upper"}
    assert p0["lower"] <= p0["yhat"] <= p0["upper"]
    assert "mape" in data["metricsInSample"]
    assert "rmse" in data["metricsInSample"]


def test_forecast_endpoint_short_series_falls_back():
    body = {"series": _series(3), "horizon": 2}
    r = client.post("/forecast", json=body)
    assert r.status_code == 200, r.text
    assert r.json()["model"] == "seasonal_naive"


def test_forecast_rejects_unsorted_dates():
    s = _series(5)
    s[0], s[1] = s[1], s[0]  # break ordering
    r = client.post("/forecast", json={"series": s, "horizon": 2})
    assert r.status_code == 422


def test_forecast_rejects_bad_horizon():
    r = client.post("/forecast", json={"series": _series(5), "horizon": 0})
    assert r.status_code == 422


def test_demo_endpoint_end_to_end():
    r = client.get("/forecast/demo")
    assert r.status_code == 200, r.text
    data = r.json()
    assert len(data["forecast"]) == 14
    assert data["model"].startswith("holt_winters")
    # demo series is large & clean -> reasonable in-sample fit
    assert data["metricsInSample"]["mape"] < 25.0
