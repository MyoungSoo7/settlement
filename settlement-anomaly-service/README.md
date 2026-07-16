# settlement-anomaly-service

Anomaly / fraud scoring on **settlement & payout** records for the settlement MSA.
Python / FastAPI microservice, port **8121**.

Given raw records `[{id, merchantId, amount, ts, type}]` it returns, per record, an
`anomalyScore ∈ [0,1]`, a boolean `isAnomaly`, and human-readable `reasons[]`
explaining which signals fired.

## Model

An **ensemble of two complementary layers** (see `src/anomaly_service/model/`):

1. **Robust statistical layer (z-score via MAD)** — `detector.py`.
   For each record it computes how far `amount` deviates from that merchant's
   **robust centre** (median), scaled by the merchant's **MAD** (median absolute
   deviation × 1.4826, a consistent estimator of σ that tolerates outliers). It
   also flags **off-hours** activity (00:00–04:59) and **amount ≫ merchant mean**
   (ratio ≥ 5×). This layer is interpretable and produces the `reasons`.
2. **IsolationForest (scikit-learn)** — an unsupervised model fit on the full
   feature matrix, catching *multivariate* outliers the univariate rules miss
   (unusual combinations of amount + hour + frequency). Its `decision_function`
   is calibrated to `[0,1]` against the training set.

**Combining:** each layer yields a score in `[0,1]`; the ensemble is
`0.55·stat + 0.45·iforest`, with a small **agreement bonus** when both layers are
confident. A record is `isAnomaly` when the ensemble score ≥ `ANOMALY_THRESHOLD`
(default 0.7). All logic is pure and reproducible under a fixed `random_state`.

### Features (`model/features.py`, deterministic)

`amount`, `log_amount` (log1p compresses the heavy tail), `hour_of_day`,
`merchant_freq`, `amount_dev_from_mean`, `amount_ratio_to_mean`.

## Endpoints

| Method | Path          | Purpose                                                        |
|--------|---------------|----------------------------------------------------------------|
| GET    | `/health`     | `{"status":"UP"}`                                              |
| POST   | `/train`      | Fit IsolationForest + per-merchant baselines from posted records (or bundled sample). Model held in-memory. |
| POST   | `/score`      | `{records:[...]}` → per-record `{id, anomalyScore, isAnomaly, reasons}` |
| GET    | `/score/demo` | Runs the bundled sample (normal + injected outliers) end-to-end |

The model is **fit-on-startup** with a bundled sample dataset (`data/`), so
`/score` works immediately without an explicit `/train` call.

## Run

```bash
# 1. install
python3.11 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

# 2. run (fits on startup)
python -m anomaly_service          # or: uvicorn anomaly_service.api.app:app --port 8121
```

### curl

```bash
curl -s localhost:8121/health
# {"status":"UP"}

curl -s localhost:8121/score/demo | python -m json.tool
# -> expectedOutliers all present in "flagged"

curl -s -X POST localhost:8121/score -H 'content-type: application/json' -d '{
  "records": [
    {"id":"spike","merchantId":"M-COFFEE","amount":180000,"ts":"2026-07-15T14:00:00Z","type":"PAYOUT"},
    {"id":"ok","merchantId":"M-COFFEE","amount":4550,"ts":"2026-07-15T10:00:00Z","type":"SETTLEMENT"}
  ]
}' | python -m json.tool
```

Example `/score` output for `spike`:

```json
{
  "id": "spike",
  "anomalyScore": 0.9591,
  "isAnomaly": true,
  "reasons": [
    "amount robust z-score 272.3 vs merchant baseline (median=4555.3)",
    "amount 40.6x the merchant mean",
    "isolation-forest flagged multivariate outlier"
  ]
}
```

## Configuration (env)

| Var                     | Default          | Meaning                                  |
|-------------------------|------------------|------------------------------------------|
| `PORT`                  | `8121`           | HTTP port                                |
| `ANOMALY_THRESHOLD`     | `0.7`            | Score ≥ this ⇒ `isAnomaly` (must be 0–1) |
| `ANOMALY_SEED`          | `42`             | RNG seed for reproducibility             |
| `ANOMALY_CONTAMINATION` | `0.05`           | IsolationForest expected outlier fraction|
| `ANOMALY_N_ESTIMATORS`  | `200`            | Number of trees                          |
| `LOG_LEVEL`             | `INFO`           | Logging level (structured JSON to stdout)|

## Tests

```bash
pip install -r requirements-dev.txt
pytest            # 17 passed
```

Covers: feature-engineering correctness & determinism, z-score/MAD flagging an
obvious amount outlier, seeded IsolationForest flagging injected outliers while
passing normal points, ensemble score ∈ [0,1], reproducibility, and the
`/health`, `/score`, `/score/demo`, `/train` endpoints via `TestClient`.

## Docker

```bash
docker build -t settlement-anomaly-service .
docker run -p 8121:8121 settlement-anomaly-service
```

`python:3.11-slim`, non-root user (`appuser`, uid 10001), `EXPOSE 8121`, uvicorn entrypoint.

## TODO

- **Label feedback loop** — capture analyst confirmations/dismissals and retrain
  a supervised layer on top of the unsupervised score.
- **Real feature store** — replace the bundled sample with historical settlement
  data + persisted per-merchant baselines (rolling windows, seasonality).
- **Streaming scoring off Kafka** — consume `lemuel.settlement.confirmed` and
  payout events, score in-flight, emit anomaly events (fits the repo's Outbox /
  at-least-once + idempotent-consumer pattern).
- **Model persistence to disk / registry** — currently in-memory only; persist
  the fitted IsolationForest + baselines (joblib / MLflow-style registry) so
  restarts and multiple replicas share a model version.
