// Package hub implements the fan-out broadcaster at the heart of the service.
//
// Model:
//   - Exactly one "quote loop" goroutine runs per active stock code. It ticks
//     on an interval, asks the QuoteSource for the next price, and broadcasts a
//     Tick to every subscriber of that code.
//   - Subscribers register with Subscribe(code) and receive a read-only channel
//     plus a cancel func. They MUST call cancel (or cancel their context) when
//     done; the Hub then removes them and, when the last subscriber of a code
//     leaves, stops that code's quote loop. No goroutines leak.
//
// Concurrency safety: all shared state lives behind a single mutex. Sends to
// subscriber channels are non-blocking (buffered + drop-oldest semantics) so a
// slow client can never stall the broadcaster or other subscribers.
package hub

import (
	"context"
	"log/slog"
	"sync"
	"time"

	"github.com/myoungsoo7/settlement/market-stream-service/internal/quote"
)

// defaultBasePrice is used when a source has no opinion (e.g. simulated with no
// polling ground truth). 70000 is a plausible KRW blue-chip price.
const defaultBasePrice = 70000.0

// subscriber is one connected client's delivery channel.
type subscriber struct {
	ch chan quote.Tick
}

// codeState holds everything for one active stock code.
type codeState struct {
	subs   map[*subscriber]struct{}
	cancel context.CancelFunc // stops the quote loop
}

// Hub is the fan-out broadcaster. Construct with New.
type Hub struct {
	source   quote.QuoteSource
	interval time.Duration
	bufSize  int
	log      *slog.Logger

	mu    sync.Mutex
	codes map[string]*codeState

	// nextPrice is pluggable so the Hub can drive any source that exposes a
	// Next(code, base) walk. Set by New based on the concrete source.
	nextPrice func(code string, base float64) float64
}

// New builds a Hub. interval is the tick cadence; bufSize is the per-subscriber
// channel buffer.
func New(source quote.QuoteSource, interval time.Duration, bufSize int, log *slog.Logger) *Hub {
	if log == nil {
		log = slog.Default()
	}
	if bufSize < 1 {
		bufSize = 1
	}
	h := &Hub{
		source:   source,
		interval: interval,
		bufSize:  bufSize,
		log:      log,
		codes:    make(map[string]*codeState),
	}
	// Wire the price generator. Both SimulatedSource and PollingSource (which
	// embeds it) expose Next.
	switch s := source.(type) {
	case interface {
		Next(string, float64) float64
	}:
		h.nextPrice = s.Next
	default:
		// Fallback: constant base price (still a valid, if boring, stream).
		h.nextPrice = func(code string, base float64) float64 { return base }
	}
	return h
}

// Subscribe registers a new subscriber for stockCode. It returns a read-only
// channel of ticks and an unsubscribe func. The caller MUST call unsubscribe
// when finished (typically via defer or when its context is cancelled).
//
// The first subscriber for a code starts that code's quote loop; the last one
// to leave stops it.
func (h *Hub) Subscribe(stockCode string) (<-chan quote.Tick, func()) {
	h.mu.Lock()
	defer h.mu.Unlock()

	cs, ok := h.codes[stockCode]
	if !ok {
		ctx, cancel := context.WithCancel(context.Background())
		cs = &codeState{
			subs:   make(map[*subscriber]struct{}),
			cancel: cancel,
		}
		h.codes[stockCode] = cs
		go h.runQuoteLoop(ctx, stockCode)
		h.log.Info("hub: started quote loop", "code", stockCode)
	}

	sub := &subscriber{ch: make(chan quote.Tick, h.bufSize)}
	cs.subs[sub] = struct{}{}
	h.log.Debug("hub: subscribed", "code", stockCode, "subscribers", len(cs.subs))

	var once sync.Once
	unsubscribe := func() {
		once.Do(func() { h.unsubscribe(stockCode, sub) })
	}
	return sub.ch, unsubscribe
}

func (h *Hub) unsubscribe(stockCode string, sub *subscriber) {
	h.mu.Lock()
	defer h.mu.Unlock()

	cs, ok := h.codes[stockCode]
	if !ok {
		return
	}
	if _, ok := cs.subs[sub]; !ok {
		return
	}
	delete(cs.subs, sub)
	close(sub.ch)
	h.log.Debug("hub: unsubscribed", "code", stockCode, "subscribers", len(cs.subs))

	if len(cs.subs) == 0 {
		cs.cancel()
		delete(h.codes, stockCode)
		h.log.Info("hub: stopped quote loop (no subscribers)", "code", stockCode)
	}
}

// runQuoteLoop is the single producer goroutine for a stock code. It runs until
// ctx is cancelled (last subscriber left, or Hub shutdown).
func (h *Hub) runQuoteLoop(ctx context.Context, stockCode string) {
	base := h.source.BasePrice(stockCode, defaultBasePrice)
	ticker := time.NewTicker(h.interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case now := <-ticker.C:
			price := h.nextPrice(stockCode, base)
			tick := quote.NewTick(stockCode, price, now)
			h.broadcast(stockCode, tick)
		}
	}
}

// broadcast sends a tick to every subscriber of a code without blocking. If a
// subscriber's buffer is full (slow client), the oldest queued tick is dropped
// to make room for the newest — a live price stream favours freshness.
func (h *Hub) broadcast(stockCode string, tick quote.Tick) {
	h.mu.Lock()
	cs, ok := h.codes[stockCode]
	if !ok {
		h.mu.Unlock()
		return
	}
	// Snapshot subscriber channels so we can release the lock before sending.
	chans := make([]chan quote.Tick, 0, len(cs.subs))
	for sub := range cs.subs {
		chans = append(chans, sub.ch)
	}
	h.mu.Unlock()

	for _, ch := range chans {
		select {
		case ch <- tick:
		default:
			// Buffer full: drop oldest, enqueue newest (best-effort).
			select {
			case <-ch:
			default:
			}
			select {
			case ch <- tick:
			default:
			}
		}
	}
}

// Shutdown stops all quote loops. Subscriber channels are closed via their
// loops ending; callers relying on Subscribe should also cancel their own
// contexts. Safe to call once during graceful shutdown.
func (h *Hub) Shutdown() {
	h.mu.Lock()
	defer h.mu.Unlock()
	for code, cs := range h.codes {
		cs.cancel()
		for sub := range cs.subs {
			close(sub.ch)
			delete(cs.subs, sub)
		}
		delete(h.codes, code)
	}
	h.log.Info("hub: shutdown complete")
}

// ActiveCodes returns the number of stock codes with a running quote loop.
// Exposed for tests and metrics.
func (h *Hub) ActiveCodes() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.codes)
}

// SubscriberCount returns the number of subscribers for a code (0 if none).
func (h *Hub) SubscriberCount(stockCode string) int {
	h.mu.Lock()
	defer h.mu.Unlock()
	cs, ok := h.codes[stockCode]
	if !ok {
		return 0
	}
	return len(cs.subs)
}
