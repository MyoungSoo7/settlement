# market-stream-service

Real-time stock price streaming for the investment / CEO dashboards, part of the
`settlement` polyglot MSA. Written in Go for its concurrency model: one
producer goroutine per stock code fans out live price ticks to any number of
connected dashboard clients over **Server-Sent Events** (and optionally
**WebSocket**).

Runs with **zero external dependencies** out of the box — the default quote
source is a deterministic simulated random walk, so `go run ./cmd/server` gives
you a live stream immediately.

- Module: `github.com/myoungsoo7/settlement/market-stream-service`
- Port: **8110** (env `MARKET_STREAM_PORT`)
- Go: 1.22+

## Endpoints

| Method | Path                  | Description |
|--------|-----------------------|-------------|
| GET    | `/healthz`            | Liveness — `200 {"status":"UP"}` |
| GET    | `/stream/{stockCode}` | **SSE** stream. `event: tick`, `data: {"stockCode","price","ts"}` every ~1s |
| GET    | `/ws/{stockCode}`     | WebSocket stream (same JSON tick payload) |

Tick payload:

```json
{ "stockCode": "005930", "price": 69410.03, "ts": "2026-07-16T05:11:50.190Z" }
```

## Run

```bash
# Simulated source (default) — no external deps
go run ./cmd/server

# Deterministic stream for demos/tests
MARKET_STREAM_SEED=42 go run ./cmd/server
```

### curl examples

```bash
# health
curl -s localhost:8110/healthz
# -> {"status":"UP"}

# live SSE stream (Ctrl-C to stop)
curl -N localhost:8110/stream/005930
# : connected
#
# event: tick
# data: {"stockCode":"005930","price":69301.23,"ts":"2026-07-16T05:11:49.990Z"}
# ...
```

## How the concurrency works

The **Hub** (`internal/hub`) is the fan-out broadcaster. On the first
subscription for a stock code it launches exactly one "quote loop" goroutine
that ticks on an interval, asks the `QuoteSource` for the next price, and
broadcasts a `Tick` to every subscriber of that code. Each HTTP/WS connection is
one subscription (a buffered channel); when it disconnects, its request context
is cancelled and it unsubscribes — and when the **last** subscriber of a code
leaves, that code's quote loop is stopped. This means **no goroutines leak**
regardless of connect/disconnect churn (covered by `TestHub_NoGoroutineLeak`).
Broadcast sends are non-blocking with drop-oldest semantics, so a slow client
can never stall the broadcaster or starve other subscribers.

## Quote sources

`QuoteSource` (`internal/quote`) is the upstream price abstraction:

- **`SimulatedSource`** (default) — a bounded random walk (±5% band, ±1%/tick)
  seeded deterministically per `(seed, stockCode)`. Set `MARKET_STREAM_SEED` for
  reproducible streams; without it the seed is time-based.
- **`PollingSource`** — wraps the simulated walk but periodically GETs the
  existing market-service
  `${MARKET_BASE_URL}/api/market/stocks/{code}/series?from=...` and uses the
  latest `closePrice` as the base price, so the numbers are grounded in real
  data while the intra-tick motion stays simulated. Enable with
  `MARKET_STREAM_SOURCE=polling`. On any upstream error it falls back to the
  simulated base, so the stream never dies.

## Environment variables

| Variable                        | Default                        | Description |
|---------------------------------|--------------------------------|-------------|
| `MARKET_STREAM_PORT`            | `8110`                         | HTTP listen port |
| `MARKET_STREAM_SOURCE`          | `simulated`                    | `simulated` or `polling` |
| `MARKET_STREAM_SEED`            | time-based                     | Seed for the simulated walk (set for determinism) |
| `MARKET_STREAM_TICK_INTERVAL`   | `1s`                           | Tick cadence (Go duration, e.g. `500ms`) |
| `MARKET_STREAM_SUB_BUFFER`      | `16`                           | Per-subscriber channel buffer |
| `MARKET_BASE_URL`               | `http://market-service:8080`   | market-service base URL (polling source) |
| `MARKET_STREAM_POLL_INTERVAL`   | `60s`                          | How often polling source refreshes the base price |

## Test

```bash
go test ./...          # unit tests: hub fan-out + leak, simulated walk, SSE handler
go test -race ./...    # race-clean
```

## Docker

Multi-stage build (golang:1.22 → distroless static, non-root, ~static binary):

```bash
docker build -t market-stream-service .
docker run --rm -p 8110:8110 market-stream-service
```

## TODO: real feed

MVP streams simulated prices (optionally base-anchored via market-service). A
real exchange / broker feed (e.g. KIS/KRX websocket push) is **out of MVP
scope**. To wire one, implement `quote.QuoteSource` (plus a `Next(code, base)`
walk or a direct tick channel) against the real feed and select it in
`cmd/server/main.go:buildSource`. The Hub, SSE/WS handlers, and client contract
stay unchanged.
