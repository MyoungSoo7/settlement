// Package quote defines the QuoteSource abstraction and its implementations.
//
// A QuoteSource is the "upstream" of a price tick: given a stock code it
// produces a stream of Tick values on a channel until its context is
// cancelled. The Hub owns exactly one running QuoteSource goroutine per
// active stock code and fans the ticks out to subscribers.
package quote

import "time"

// Value source markers. These answer the only question a consumer really has
// about a number on a screen: can I trust it?
//
// The vocabulary is deliberately identical to market-service's ValueSource
// domain enum. Two services describing the same distinction with different
// words is how a consumer ends up guessing.
const (
	// SourceSample marks a synthetic price. It must never be used for
	// valuation, alerting, or anything that looks like a trading decision.
	SourceSample = "SAMPLE"
	// SourceExchange marks a real exchange-published quote. Nothing in this
	// service emits it yet — it exists so a real feed has a name to claim.
	SourceExchange = "EXCHANGE"
)

// Tick is a single price observation for a stock.
type Tick struct {
	StockCode string  `json:"stockCode"`
	Price     float64 `json:"price"`
	// Ts is the tick timestamp in RFC3339 (millisecond precision) so the
	// JSON payload is directly consumable by dashboard clients.
	Ts string `json:"ts"`
	// Source is SourceSample or SourceExchange. It rides on every tick rather
	// than being announced once at stream open, because a consumer that joins
	// mid-stream (or reconnects) would otherwise never learn it.
	Source string `json:"source"`
}

// NewTick builds a Tick stamping the supplied time and its value source.
func NewTick(stockCode string, price float64, t time.Time, source string) Tick {
	return Tick{
		StockCode: stockCode,
		Price:     round2(price),
		Ts:        t.UTC().Format("2006-01-02T15:04:05.000Z07:00"),
		Source:    source,
	}
}

func round2(v float64) float64 {
	// Prices are KRW; two decimals is plenty and keeps JSON tidy.
	return float64(int64(v*100+0.5)) / 100
}

// QuoteSource produces a stream of ticks for a single stock code.
//
// Implementations MUST:
//   - return a channel that is closed when ctx is cancelled (so the Hub can
//     detect source termination and clean up), and
//   - never block forever on send: they should honour ctx.Done().
//
// The interval hint is how often the Hub would like a tick; a source may
// treat it as advisory.
type QuoteSource interface {
	// BasePrice returns the seed price used for a stock code. Sources that
	// have no opinion may return the supplied fallback.
	BasePrice(stockCode string, fallback float64) float64
	// Name identifies the source implementation (for logging/metrics).
	Name() string
	// ValueSource reports whether the ticks this source produces may be
	// trusted: SourceSample or SourceExchange. Name() answers "which
	// implementation"; this answers "is it real", and only the latter belongs
	// in a payload leaving the service.
	ValueSource() string
}
