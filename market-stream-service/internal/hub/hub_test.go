package hub

import (
	"io"
	"log/slog"
	"runtime"
	"sync"
	"testing"
	"time"

	"github.com/myoungsoo7/settlement/market-stream-service/internal/quote"
)

func quietLog() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func newTestHub() *Hub {
	// Fast ticks so tests don't drag.
	return New(quote.NewSimulatedSource(99), 5*time.Millisecond, 8, quietLog())
}

func TestHub_SubscribeReceivesTicks(t *testing.T) {
	h := newTestHub()
	defer h.Shutdown()

	ch, unsub := h.Subscribe("005930")
	defer unsub()

	select {
	case tick := <-ch:
		if tick.StockCode != "005930" {
			t.Fatalf("unexpected code: %q", tick.StockCode)
		}
		if tick.Price <= 0 {
			t.Fatalf("non-positive price: %v", tick.Price)
		}
	case <-time.After(time.Second):
		t.Fatal("no tick received within 1s")
	}
}

func TestHub_FanOutToMultipleSubscribers(t *testing.T) {
	h := newTestHub()
	defer h.Shutdown()

	const n = 20
	chans := make([]<-chan quote.Tick, n)
	unsubs := make([]func(), n)
	for i := 0; i < n; i++ {
		chans[i], unsubs[i] = h.Subscribe("000660")
	}
	defer func() {
		for _, u := range unsubs {
			u()
		}
	}()

	if got := h.SubscriberCount("000660"); got != n {
		t.Fatalf("subscriber count: got %d want %d", got, n)
	}
	if got := h.ActiveCodes(); got != 1 {
		t.Fatalf("active codes: got %d want 1", got)
	}

	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(ch <-chan quote.Tick) {
			defer wg.Done()
			select {
			case <-ch:
			case <-time.After(time.Second):
				t.Error("subscriber got no tick")
			}
		}(chans[i])
	}
	wg.Wait()
}

func TestHub_UnsubscribeStopsQuoteLoop(t *testing.T) {
	h := newTestHub()
	defer h.Shutdown()

	_, unsub1 := h.Subscribe("035720")
	_, unsub2 := h.Subscribe("035720")

	if h.ActiveCodes() != 1 {
		t.Fatalf("expected 1 active code")
	}

	unsub1()
	if got := h.SubscriberCount("035720"); got != 1 {
		t.Fatalf("after one unsub: got %d want 1", got)
	}
	if h.ActiveCodes() != 1 {
		t.Fatal("quote loop stopped too early")
	}

	unsub2()
	// Last subscriber gone -> code state removed.
	if got := h.ActiveCodes(); got != 0 {
		t.Fatalf("after last unsub: active codes %d want 0", got)
	}
	if got := h.SubscriberCount("035720"); got != 0 {
		t.Fatalf("subscriber count %d want 0", got)
	}
}

func TestHub_NoGoroutineLeak(t *testing.T) {
	// Warm up the runtime, then measure baseline.
	h := newTestHub()
	ch, unsub := h.Subscribe("warmup")
	<-ch
	unsub()
	time.Sleep(30 * time.Millisecond)

	runtime.GC()
	before := runtime.NumGoroutine()

	// Churn: subscribe & unsubscribe across many codes and subscribers.
	for round := 0; round < 50; round++ {
		var unsubs []func()
		for i := 0; i < 10; i++ {
			_, u := h.Subscribe("code-" + string(rune('A'+i)))
			unsubs = append(unsubs, u)
		}
		for _, u := range unsubs {
			u()
		}
	}
	h.Shutdown()

	// Give loops a moment to exit.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		runtime.GC()
		if runtime.NumGoroutine() <= before+2 {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}

	after := runtime.NumGoroutine()
	if after > before+2 {
		t.Fatalf("goroutine leak: before=%d after=%d", before, after)
	}
}

func TestHub_SlowSubscriberDoesNotBlockOthers(t *testing.T) {
	// Tiny buffer + never-read slow subscriber must not stall a fast one.
	h := New(quote.NewSimulatedSource(1), 2*time.Millisecond, 1, quietLog())
	defer h.Shutdown()

	_, slowUnsub := h.Subscribe("SLOW") // never read
	defer slowUnsub()
	fast, fastUnsub := h.Subscribe("SLOW")
	defer fastUnsub()

	count := 0
	timeout := time.After(500 * time.Millisecond)
	for count < 3 {
		select {
		case <-fast:
			count++
		case <-timeout:
			t.Fatalf("fast subscriber starved: only %d ticks", count)
		}
	}
}

// TestHub_UnsubscribeDuringBroadcast is a regression test for a
// send-on-closed-channel panic. broadcast used to snapshot the subscriber
// channels, release the mutex, and only then send. unsubscribe could close a
// channel inside that window, leaving the broadcaster to send on an
// already-closed channel — which the Go spec defines as a run-time panic, not a
// recoverable error. The panic killed the process, so one client leaving took
// every other connected client down with it.
//
// The churn below is deliberately hostile: many subscribers on a single code, a
// tick interval short enough that a broadcast is almost always in flight, and
// repeated mass unsubscribes from separate goroutines.
func TestHub_UnsubscribeDuringBroadcast(t *testing.T) {
	const (
		subscribers = 200
		rounds      = 40
	)
	// 1ms ticks: the quote loop is broadcasting near-continuously.
	h := New(quote.NewSimulatedSource(7), time.Millisecond, 4, quietLog())
	defer h.Shutdown()

	// Anchor keeps the code's quote loop alive across rounds, so every round
	// races against a running broadcaster rather than a cold start.
	_, anchor := h.Subscribe("005930")
	defer anchor()

	for round := 0; round < rounds; round++ {
		unsubs := make([]func(), subscribers)
		for i := range unsubs {
			// Deliberately never read: buffers fill, so broadcast also
			// exercises its drop-oldest path against a closing channel.
			_, unsubs[i] = h.Subscribe("005930")
		}
		var wg sync.WaitGroup
		wg.Add(len(unsubs))
		for _, unsub := range unsubs {
			go func(u func()) {
				defer wg.Done()
				u()
			}(unsub)
		}
		wg.Wait()
	}

	if got := h.SubscriberCount("005930"); got != 1 {
		t.Fatalf("subscriber count %d want 1 (anchor only)", got)
	}
}
