package idempotency

import (
	"context"
	"testing"
	"time"
)

func TestMemoryStore_LenTracksDistinctKeys(t *testing.T) {
	s := NewMemoryStore(time.Hour)
	ctx := context.Background()

	if s.Len() != 0 {
		t.Fatalf("a fresh store must be empty, got %d", s.Len())
	}

	if _, err := s.MarkIfNew(ctx, "PAYMENT:pk_1"); err != nil {
		t.Fatalf("mark: %v", err)
	}
	if _, err := s.MarkIfNew(ctx, "PAYMENT:pk_2"); err != nil {
		t.Fatalf("mark: %v", err)
	}
	// A repeat of an existing key must not grow the store.
	if _, err := s.MarkIfNew(ctx, "PAYMENT:pk_1"); err != nil {
		t.Fatalf("mark: %v", err)
	}

	if s.Len() != 2 {
		t.Fatalf("expected 2 tracked keys, got %d", s.Len())
	}
}

func TestMemoryStore_ExpiredKeyIsAcceptedAgain(t *testing.T) {
	s := NewMemoryStore(time.Minute)
	now := time.Date(2026, 8, 22, 0, 0, 0, 0, time.UTC)
	s.now = func() time.Time { return now }
	ctx := context.Background()

	if isNew, _ := s.MarkIfNew(ctx, "PAYMENT:pk_1"); !isNew {
		t.Fatal("first delivery must be new")
	}
	if isNew, _ := s.MarkIfNew(ctx, "PAYMENT:pk_1"); isNew {
		t.Fatal("a delivery inside the TTL is a duplicate")
	}

	// Past the TTL the key is re-markable — the store is a dedupe window, not a
	// permanent ledger.
	now = now.Add(2 * time.Minute)
	if isNew, _ := s.MarkIfNew(ctx, "PAYMENT:pk_1"); !isNew {
		t.Fatal("after the TTL the key must be accepted again")
	}
	if s.Len() != 1 {
		t.Fatalf("re-marking must replace, not duplicate; got %d", s.Len())
	}
}
