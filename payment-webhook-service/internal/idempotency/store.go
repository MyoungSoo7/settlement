// Package idempotency provides deduplication for webhook processing so a
// payment event is published at most once even if Toss retries the webhook.
package idempotency

import (
	"context"
	"sync"
	"time"
)

// IdempotencyStore records processed webhook keys so duplicates can be detected.
// Implementations must be safe for concurrent use.
type IdempotencyStore interface {
	// MarkIfNew atomically records key. It returns true if key was newly stored
	// (i.e. this is the first time we've seen it), or false if key already
	// existed (a duplicate). This must be a single atomic check-and-set to avoid
	// races between concurrent duplicate deliveries.
	MarkIfNew(ctx context.Context, key string) (isNew bool, err error)
}

// entry holds an expiry timestamp for a stored key.
type entry struct {
	expiresAt time.Time
}

// MemoryStore is an in-memory TTL implementation of IdempotencyStore.
// Suitable for the MVP / single instance; see README TODO for durable stores.
type MemoryStore struct {
	mu    sync.Mutex
	ttl   time.Duration
	items map[string]entry
	now   func() time.Time // injectable clock for tests
}

// NewMemoryStore builds a MemoryStore where keys expire after ttl.
func NewMemoryStore(ttl time.Duration) *MemoryStore {
	return &MemoryStore{
		ttl:   ttl,
		items: make(map[string]entry),
		now:   time.Now,
	}
}

// MarkIfNew atomically checks and sets key, lazily evicting expired entries.
func (s *MemoryStore) MarkIfNew(_ context.Context, key string) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now()
	if e, ok := s.items[key]; ok && now.Before(e.expiresAt) {
		return false, nil // still-live duplicate
	}
	// New, or previously-expired key: (re)mark it.
	s.items[key] = entry{expiresAt: now.Add(s.ttl)}
	return true, nil
}

// Len reports the number of tracked keys (including not-yet-evicted expired
// ones). Primarily useful for tests and diagnostics.
func (s *MemoryStore) Len() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.items)
}
