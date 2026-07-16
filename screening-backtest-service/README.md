# screening-backtest-service

Backtests the investment stock-screening **trade-plan** rules over historical
prices and reports risk/return metrics. Python / FastAPI. Part of the
`settlement` polyglot MSA. Port **8120**.

## Purpose

Given a set of screening picks (each with a signal date and current price) plus
historical daily closes, this service:

1. Builds the deterministic **trade plan** for each pick — reimplemented
   faithfully from `investment-service`'s `TradePlanPolicy`:
   - 3 tranche entries at **100% / 95% / 90%** of current price, weights
     **30% / 30% / 40%**;
   - all prices rounded **down** to the KRX tick (2023-01 reform table);
   - **stop-loss = avg entry × 0.93**, **take-profit = avg entry × 1.20**;
   - fees / taxes / slippage are **not** modelled.
2. Simulates the forward path from the signal date: exit at **TAKE_PROFIT** if
   the take level is reached, **STOP_LOSS** if the stop is breached, otherwise
   **HORIZON** (mark-to-market at the end of the horizon window).
3. Aggregates portfolio metrics: total return, CAGR, max drawdown (MDD),
   Sharpe (rf=0), win rate, avg win / avg loss, num trades.

## Run

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt

# start the server (honours $PORT, default 8120)
PYTHONPATH=src python -m screening_backtest
# or directly with uvicorn:
PYTHONPATH=src uvicorn screening_backtest.api.app:app --host 0.0.0.0 --port 8120
```

Health check and end-to-end demo (zero external input — uses the bundled
`data/sample_backtest.json`):

```bash
curl -s localhost:8120/health
# {"status":"UP"}

curl -s localhost:8120/backtest/demo | python -m json.tool
```

Custom backtest:

```bash
curl -s -X POST localhost:8120/backtest \
  -H 'content-type: application/json' \
  -d '{
    "horizonDays": 5,
    "budget": 10000000,
    "entries": [{"stockCode":"AAA","signalDate":"2026-01-01","currentPrice":10000}],
    "priceSeries": {"AAA": [
      {"date":"2026-01-01","close":10000},
      {"date":"2026-01-02","close":10500},
      {"date":"2026-01-03","close":12500}
    ]}
  }'
```

## Endpoints

| Method | Path              | Purpose                                             |
|--------|-------------------|-----------------------------------------------------|
| GET    | `/health`         | Liveness — `{"status":"UP"}`                         |
| POST   | `/backtest`       | Run picks through trade-plan + exit sim, aggregate  |
| GET    | `/backtest/demo`  | Run the bundled sample dataset end-to-end           |

`POST /backtest` body:

```json
{
  "entries": [{"stockCode": "...", "signalDate": "YYYY-MM-DD", "currentPrice": 0}],
  "priceSeries": {"stockCode": [{"date": "YYYY-MM-DD", "close": 0}]},
  "horizonDays": 20,
  "budget": 10000000
}
```

`budget` is optional; when omitted, entry levels are computed without share
quantities (weighted-average of the tick-rounded band prices).

## Env

| Var               | Default                        | Meaning                          |
|-------------------|--------------------------------|----------------------------------|
| `PORT`            | `8120`                         | HTTP port                        |
| `LOG_LEVEL`       | `INFO`                         | Log level (structured JSON logs) |
| `MARKET_BASE_URL` | `http://market-service:8080`   | Market-service base (see TODO)   |

## Tests

```bash
PYTHONPATH=src pytest       # (pytest config already sets pythonpath=src)
```

Covers: KRX tick rounding at band boundaries, trade-plan invariant
(stop < entry < take), exit simulation for each reason, and metrics math
(MDD / Sharpe / win-rate) against a known equity curve.

## Docker

```bash
docker build -t screening-backtest-service .
docker run -p 8120:8120 screening-backtest-service
```

Runs as non-root, `EXPOSE 8120`, uvicorn entrypoint.

## TODO

- **Pull real history** from market-service instead of requiring inline
  `priceSeries`: `GET ${MARKET_BASE_URL}/api/market/stocks/{code}/series`.
- **Transaction costs / slippage**: brokerage fee, KRX tax (0.18% sell),
  and fill slippage on tranche entries — currently zero-cost.
- **Walk-forward** evaluation: rolling in-sample/out-of-sample windows rather
  than a single horizon per signal.
- Per-tranche fill accounting (partial fills when only some bands are touched
  before exit) — MVP assumes all bands fill at the signal.
- Annualized (per-period) Sharpe vs. the current per-trade Sharpe.
