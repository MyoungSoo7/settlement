"""Endpoint tests via FastAPI TestClient."""
from fastapi.testclient import TestClient

from anomaly_service.api.app import create_app


def _client():
    app = create_app()
    return TestClient(app)


def test_health():
    with _client() as c:
        r = c.get("/health")
        assert r.status_code == 200
        assert r.json() == {"status": "UP"}


def test_score_flags_outlier():
    with _client() as c:
        body = {
            "records": [
                {
                    "id": "spike",
                    "merchantId": "M-COFFEE",
                    "amount": 180000.0,
                    "ts": "2026-07-15T14:00:00Z",
                    "type": "PAYOUT",
                },
                {
                    "id": "ok",
                    "merchantId": "M-COFFEE",
                    "amount": 4550.0,
                    "ts": "2026-07-15T10:00:00Z",
                    "type": "SETTLEMENT",
                },
            ]
        }
        r = c.post("/score", json=body)
        assert r.status_code == 200
        data = r.json()
        results = {x["id"]: x for x in data["results"]}
        assert results["spike"]["isAnomaly"] is True
        assert results["ok"]["isAnomaly"] is False
        assert results["spike"]["reasons"]


def test_score_demo_flags_all_injected():
    with _client() as c:
        r = c.get("/score/demo")
        assert r.status_code == 200
        data = r.json()
        for oid in data["expectedOutliers"]:
            assert oid in data["flagged"], f"{oid} missing from flagged"


def test_train_with_posted_records():
    with _client() as c:
        body = {
            "records": [
                {"id": f"t{i}", "merchantId": "M-X", "amount": 100.0 + i,
                 "ts": "2026-07-15T09:00:00Z", "type": "SETTLEMENT"}
                for i in range(20)
            ]
        }
        r = c.post("/train", json=body)
        assert r.status_code == 200
        data = r.json()
        assert data["status"] == "trained"
        assert data["trainedRecords"] == 20
        assert data["merchants"] == 1
