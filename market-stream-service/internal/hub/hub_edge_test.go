package hub

import (
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/myoungsoo7/settlement/market-stream-service/internal/quote"
)

// flatSource 는 Next 를 노출하지 않는 QuoteSource 다. Hub 는 이런 소스도 받아야 하고,
// 그때는 base 를 그대로 흘리는 상수 스트림이 된다(죽은 스트림이 아니라).
type flatSource struct{ base float64 }

func (f flatSource) BasePrice(string, float64) float64 { return f.base }
func (f flatSource) Name() string                      { return "flat" }
func (f flatSource) ValueSource() string               { return quote.SourceSample }

func quiet() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

func TestNew_FallsBackToConstantPriceWhenSourceHasNoWalk(t *testing.T) {
	h := New(flatSource{base: 12345}, 5*time.Millisecond, 4, quiet())
	defer h.Shutdown()

	ticks, unsub := h.Subscribe("005930")
	defer unsub()

	select {
	case tk := <-ticks:
		if tk.Price != 12345 {
			t.Fatalf("Next 없는 소스는 base 를 그대로 내보내야 한다: got %v", tk.Price)
		}
		if tk.Source != quote.SourceSample {
			t.Fatalf("source 표기가 소스의 선언과 달라졌다: %q", tk.Source)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Next 없는 소스에서 틱이 하나도 나오지 않았다")
	}
}

func TestNew_NilLoggerAndTinyBufferAreNormalized(t *testing.T) {
	h := New(flatSource{base: 1}, time.Hour, 0, nil)
	defer h.Shutdown()

	if h.log == nil {
		t.Fatal("nil 로거는 slog.Default 로 대체돼야 한다")
	}
	if h.bufSize < 1 {
		t.Fatalf("버퍼 0 은 매 틱을 드롭시킨다 — 최소 1 로 올라가야 한다: got %d", h.bufSize)
	}
}

// Shutdown 은 살아 있는 구독까지 정리한다 — 채널이 닫혀야 소비자 루프가 끝난다.
func TestShutdown_ClosesLiveSubscribers(t *testing.T) {
	h := New(quote.NewSimulatedSource(7), time.Hour, 2, quiet())

	ticksA, _ := h.Subscribe("005930")
	ticksB, _ := h.Subscribe("000660")
	if h.ActiveCodes() != 2 {
		t.Fatalf("종목 루프가 2개여야 한다: got %d", h.ActiveCodes())
	}

	h.Shutdown()

	for name, ch := range map[string]<-chan quote.Tick{"005930": ticksA, "000660": ticksB} {
		select {
		case _, open := <-ch:
			if open {
				t.Fatalf("%s: 채널이 닫히지 않았다", name)
			}
		case <-time.After(time.Second):
			t.Fatalf("%s: Shutdown 후에도 채널이 열려 있다", name)
		}
	}
	if h.ActiveCodes() != 0 {
		t.Fatalf("Shutdown 후 남은 종목 루프: %d", h.ActiveCodes())
	}
	if h.SubscriberCount("005930") != 0 {
		t.Fatalf("Shutdown 후 남은 구독자: %d", h.SubscriberCount("005930"))
	}
}

// unsubscribe 는 두 번 불려도 안전해야 한다 — 두 번째 close 는 panic 이다.
func TestUnsubscribe_IsIdempotent(t *testing.T) {
	h := New(quote.NewSimulatedSource(7), time.Hour, 2, quiet())
	defer h.Shutdown()

	_, unsub := h.Subscribe("005930")
	unsub()
	unsub() // 두 번째 호출이 panic 하면 이 테스트가 죽는다

	if h.SubscriberCount("005930") != 0 {
		t.Fatalf("구독이 남아 있다: %d", h.SubscriberCount("005930"))
	}
	if h.ActiveCodes() != 0 {
		t.Fatalf("마지막 구독자가 떠나면 종목 루프도 멈춰야 한다: got %d", h.ActiveCodes())
	}
}

// Shutdown 이후의 unsubscribe 도 조용히 무시돼야 한다(코드 자체가 사라진 상태).
func TestUnsubscribeAfterShutdownIsSafe(t *testing.T) {
	h := New(quote.NewSimulatedSource(7), time.Hour, 2, quiet())

	_, unsub := h.Subscribe("005930")
	h.Shutdown()
	unsub()
}

// 느린 구독자가 있어도 브로드캐스트는 막히지 않는다 — 가장 오래된 틱을 버리고 최신을 넣는다.
func TestBroadcast_DropsOldestForSlowSubscriber(t *testing.T) {
	h := New(flatSource{base: 100}, 2*time.Millisecond, 1, quiet())
	defer h.Shutdown()

	ticks, unsub := h.Subscribe("005930")
	defer unsub()

	// 아무것도 읽지 않고 기다린다 — 버퍼(1)가 가득 찬 뒤에도 브로드캐스터가 살아 있어야 한다.
	time.Sleep(120 * time.Millisecond)

	select {
	case tk := <-ticks:
		if tk.StockCode != "005930" {
			t.Fatalf("예상 밖 종목: %s", tk.StockCode)
		}
	case <-time.After(time.Second):
		t.Fatal("느린 구독자에게 최신 틱이 전달되지 않았다 — 브로드캐스터가 멈춘 것")
	}
}

// 구독자가 하나도 없는 종목으로의 브로드캐스트는 무해해야 한다.
func TestBroadcast_UnknownCodeIsNoOp(t *testing.T) {
	h := New(flatSource{base: 1}, time.Hour, 1, quiet())
	defer h.Shutdown()

	h.broadcast("999999", quote.NewTick("999999", 1, time.Now(), quote.SourceSample))
}
