# forecast-service

Time-series forecasting of settlement amount / revenue for the **CEO dashboard**.
Part of the settlement polyglot MSA. Python / FastAPI, port **8122**.

Given a dated series of daily settlement (or revenue) figures, it returns a
point forecast with 95% prediction intervals and in-sample accuracy metrics
(MAPE, RMSE). It uses classical, lightweight statistical models — **no
Prophet, no deep learning** — so it starts fast and runs in a small container.

## Models

Pluggable via a `Forecaster` protocol (`src/forecast_service/types.py`):

| name | model | interval |
|------|-------|----------|
| `seasonal_naive` | Repeats the last observed season (degrades to naive "repeat last value" when no season). | ±1.96·σ of seasonal residuals |
| `holt_winters` / `auto` | Holt-Winters ExponentialSmoothing (statsmodels), additive trend + **auto-selected** additive seasonality when the series has enough full cycles. | ±1.96·σ of fit residuals |

**Auto-selection & graceful degradation** (in `models.py`):
- Seasonality is enabled only when `n ≥ max(min_seasons·m + 1, 2·m + 1)` — otherwise it drops to **trend-only** (`holt_winters_trend`).
- Series with `n < 4` fall back to **seasonal-naive** (`seasonal_naive`), so short histories never error.
- If a Holt-Winters fit throws, it falls back to seasonal-naive rather than failing the request.

Everything is **deterministic**: identical input yields identical output (RNG in the demo generator is seeded).

## Run

```bash
python3.11 -m venv .venv && source .venv/bin/activate
pip install -r requirements-dev.txt        # runtime + pytest/httpx
python -m forecast_service.demo_data        # (re)generate data/demo_settlement_daily.csv
PYTHONPATH=src python -m forecast_service.main    # serves on :8122
```

### curl

```bash
# Health
curl -s localhost:8122/health
# -> {"status":"UP"}

# Bundled synthetic trend+weekly-seasonal series, forecast 14 days
curl -s localhost:8122/forecast/demo | python -m json.tool
# -> {"model":"holt_winters_seasonal","forecast":[...14 pts...],
#     "metricsInSample":{"mape":1.29,"rmse":2590.7}}

# Your own series
curl -s localhost:8122/forecast -H 'content-type: application/json' -d '{
  "series":[{"date":"2025-01-01","value":100},{"date":"2025-01-02","value":110},
            {"date":"2025-01-03","value":121},{"date":"2025-01-04","value":133}],
  "horizon":3, "seasonPeriod":7, "model":"auto"
}' | python -m json.tool
```

## Endpoints

| method | path | description |
|--------|------|-------------|
| GET | `/health` | `{"status":"UP"}` |
| POST | `/forecast` | body `{series:[{date,value}...], horizon, seasonPeriod?, model?}` → `{model, forecast:[{date,yhat,lower,upper}...], metricsInSample:{mape,rmse}}`. Series must be sorted ascending by date. |
| GET | `/forecast/demo` | Runs the bundled synthetic series (`data/`) end-to-end with a 14-day horizon and weekly season. |

## Config (env)

| var | default | meaning |
|-----|---------|---------|
| `PORT` | `8122` | listen port |
| `HOST` | `0.0.0.0` | bind address |
| `LOG_LEVEL` | `INFO` | log level (structured JSON logging to stdout) |
| `MIN_SEASONS_FOR_SEASONAL` | `2` | full cycles required before seasonal HW is attempted |

## Test

```bash
PYTHONPATH=src pytest -q      # 26 tests
```

Covers: seasonal-naive repeats the last season; Holt-Winters direction/level on a
clean linear+seasonal series within tolerance; MAPE/RMSE on known arrays;
short-series fallback; train/test backtest; and all HTTP endpoints via
`TestClient`.

## Docker

```bash
docker build -t forecast-service .
docker run -p 8122:8122 forecast-service     # non-root, uvicorn entrypoint
```

## Layout

```
src/forecast_service/
  metrics.py      # pure MAPE / RMSE
  models.py       # SeasonalNaive + HoltWinters forecasters, factory
  service.py      # dated forecast orchestration + backtest_split evaluator
  demo_data.py    # seeded synthetic settlement series -> data/*.csv
  schemas.py      # pydantic request/response
  app.py          # FastAPI app (create_app)
  main.py         # uvicorn entrypoint
  config.py       # env-driven Settings
  logging_config.py  # JSON logging
data/               # bundled demo CSV
tests/              # pytest (metrics, models, service, api)
```

## TODO

- **Pull real history** from the `settlement` / `economics` services instead of the synthetic demo series (dedicated read endpoint or a shared read-model / OLAP query).
- **Exogenous regressors** (holidays, promotions, macro indicators from `economics-service`) via SARIMAX or Holt-Winters with damping, to lift accuracy on structural breaks.
- **Model selection & backtesting cadence**: rolling-origin cross-validation (`backtest_split` is the seed of this), automatic pick of `seasonal_naive` vs `holt_winters` per series by out-of-sample MAPE, scheduled nightly re-fit.
- Multiplicative seasonality / log transform for series with variance that scales with level.
- Persist fitted models + surface confidence-level and horizon as request params.
