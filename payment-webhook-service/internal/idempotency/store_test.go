package idempotency

import (
	"context"
	"testing"
	"time"
)

func TestMemoryStore_MarkIfNew_DedupesSameKey(t *testing.T) {
	s := NewMemoryStore(time.Hour)
	ctx := context.Background()

	isNew, err := s.MarkIfNew(ctx, "PAYMENT:pk_1")
	if err != nil {
		t.Fatalf("unexpected err: %v", err)
	}
	if !isNew {
		t.Fatal("first insert should be new")
	}

	isNew, err = s.MarkIfNew(ctx, "PAYMENT:pk_1")
	if err != nil {
		t.Fatalf("unexpected err: %v", err)
	}
	if isNew {
		t.Fatal("second insert of same key should be a duplicate")
	}
}

func TestMemoryStore_DifferentKeysAreIndependent(t *testing.T) {
	s := NewMemoryStore(time.Hour)
	ctx := context.Background()

	if isNew, _ := s.MarkIfNew(ctx, "a"); !isNew {
		t.Fatal("key a should be new")
	}
	if isNew, _ := s.MarkIfNew(ctx, "b"); !isNew {
		t.Fatal("key b should be new")
	}
}

func TestMemoryStore_TTLExpiry(t *testing.T) {
	s := NewMemoryStore(time.Minute)
	ctx := context.Background()

	// Freeze clock at t0.
	now := time.Now()
	s.now = func() time.Time { return now }

	if isNew, _ := s.MarkIfNew(ctx, "pk"); !isNew {
		t.Fatal("first should be new")
	}
	if isNew, _ := s.MarkIfNew(ctx, "pk"); isNew {
		t.Fatal("within TTL should be duplicate")
	}

	// Advance past TTL.
	now = now.Add(2 * time.Minute)
	if isNew, _ := s.MarkIfNew(ctx, "pk"); !isNew {
		t.Fatal("after TTL expiry the key should be treated as new again")
	}
}
