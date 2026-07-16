package quote

import (
	"hash/fnv"
	"math"
	"math/rand"
	"sync"
)

// SimulatedSource is the zero-external-dependency MVP QuoteSource. It performs
// a bounded random walk around a base price so the stream looks alive without
// any real feed.
//
// Determinism: the walk is driven by a *math/rand.Rand seeded from a fixed
// base seed XOR'd with a hash of the stock code. For a given (seed, stockCode)
// the sequence of ticks is fully reproducible, which makes it testable.
type SimulatedSource struct {
	seed int64
	// bandPct is the max fractional deviation allowed from the base price
	// (e.g. 0.05 = walk stays within +/-5% of base).
	bandPct float64
	// stepPct is the max fractional move per tick.
	stepPct float64

	mu    sync.Mutex
	rngs  map[string]*rand.Rand
	price map[string]float64
	base  map[string]float64
}

// NewSimulatedSource builds a SimulatedSource. A stable seed makes tests
// deterministic; in production it can be time-based.
func NewSimulatedSource(seed int64) *SimulatedSource {
	return &SimulatedSource{
		seed:    seed,
		bandPct: 0.05,
		stepPct: 0.01,
		rngs:    make(map[string]*rand.Rand),
		price:   make(map[string]float64),
		base:    make(map[string]float64),
	}
}

// Name implements QuoteSource.
func (s *SimulatedSource) Name() string { return "simulated" }

// BasePrice implements QuoteSource. The simulated source has no external
// knowledge, so it simply adopts (and remembers) the fallback as the base.
func (s *SimulatedSource) BasePrice(stockCode string, fallback float64) float64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	if b, ok := s.base[stockCode]; ok {
		return b
	}
	s.base[stockCode] = fallback
	return fallback
}

// SetBase overrides the base price for a stock code (used when a PollingSource
// or a caller supplies a real latest close). It resets the current walk price.
func (s *SimulatedSource) SetBase(stockCode string, base float64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.base[stockCode] = base
	s.price[stockCode] = base
}

// Next advances the bounded random walk for stockCode and returns the new
// price. It is safe for concurrent use and deterministic per (seed, code).
func (s *SimulatedSource) Next(stockCode string, base float64) float64 {
	s.mu.Lock()
	defer s.mu.Unlock()

	r, ok := s.rngs[stockCode]
	if !ok {
		r = rand.New(rand.NewSource(s.seed ^ hashCode(stockCode)))
		s.rngs[stockCode] = r
		s.base[stockCode] = base
		s.price[stockCode] = base
	}
	if _, ok := s.base[stockCode]; !ok {
		s.base[stockCode] = base
	}

	b := s.base[stockCode]
	cur := s.price[stockCode]
	if cur == 0 {
		cur = b
	}

	// Random step in [-stepPct, +stepPct] of the base price.
	delta := (r.Float64()*2 - 1) * s.stepPct * b
	next := cur + delta

	// Clamp to the band around the base price.
	low := b * (1 - s.bandPct)
	high := b * (1 + s.bandPct)
	next = math.Max(low, math.Min(high, next))

	s.price[stockCode] = next
	return next
}

func hashCode(s string) int64 {
	h := fnv.New64a()
	_, _ = h.Write([]byte(s))
	return int64(h.Sum64())
}
