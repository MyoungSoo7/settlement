"""End-to-end API tests via FastAPI TestClient."""

from fastapi.testclient import TestClient

from screening_backtest.api.app import app

client = TestClient(app)


def test_health():
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "UP"}


def test_demo_runs_end_to_end():
    r = client.get("/backtest/demo")
    assert r.status_code == 200
    body = r.json()
    assert body["portfolio"]["numTrades"] == 3
    assert len(body["picks"]) == 3
    # Samsung pick (005930) rallies to take-profit.
    samsung = next(p for p in body["picks"] if p["stockCode"] == "005930")
    assert samsung["exitReason"] == "TAKE_PROFIT"
    # SK hynix (000660) crashes to stop-loss.
    hynix = next(p for p in body["picks"] if p["stockCode"] == "000660")
    assert hynix["exitReason"] == "STOP_LOSS"


def test_backtest_endpoint():
    payload = {
        "horizonDays": 5,
        "budget": 10000000,
        "entries": [
            {"stockCode": "AAA", "signalDate": "2026-01-01", "currentPrice": 10000}
        ],
        "priceSeries": {
            "AAA": [
                {"date": "2026-01-01", "close": 10000},
                {"date": "2026-01-02", "close": 10500},
                {"date": "2026-01-03", "close": 12500},
            ]
        },
    }
    r = client.post("/backtest", json=payload)
    assert r.status_code == 200
    body = r.json()
    assert body["portfolio"]["numTrades"] == 1
    assert body["picks"][0]["exitReason"] == "TAKE_PROFIT"
