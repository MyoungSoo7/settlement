package quote

import (
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// quietLogger 는 폴링 실패 경로가 테스트 출력을 붉게 물들이지 않게 한다.
// 실패해도 스트림이 죽지 않는 것이 이 소스의 계약이므로 WARN 은 정상 동작의 일부다.
func quietLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(newDiscard(), &slog.HandlerOptions{Level: slog.LevelError}))
}

type discardWriter struct{}

func (discardWriter) Write(p []byte) (int, error) { return len(p), nil }

func newDiscard() discardWriter { return discardWriter{} }

func seriesServer(t *testing.T, body string, status int, hits *int32) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if hits != nil {
			atomic.AddInt32(hits, 1)
		}
		if !strings.HasPrefix(r.URL.Path, "/api/market/stocks/") {
			t.Errorf("예상 밖 경로: %s", r.URL.Path)
		}
		if r.URL.Query().Get("from") == "" {
			t.Error("from 파라미터가 없으면 시리즈가 비어 돌아올 수 있다")
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(srv.Close)
	return srv
}

func TestPollingSource_NameIsPolling(t *testing.T) {
	p := NewPollingSource(NewSimulatedSource(1), "http://x", time.Minute, quietLogger())
	if p.Name() != "polling" {
		t.Fatalf("Name(): got %q, want %q", p.Name(), "polling")
	}
}

func TestPollingSource_NilLoggerFallsBack(t *testing.T) {
	p := NewPollingSource(NewSimulatedSource(1), "http://x", time.Minute, nil)
	if p.log == nil {
		t.Fatal("nil 로거는 slog.Default 로 대체돼야 한다 — nil 이면 첫 폴링 실패에서 panic 이다")
	}
}

// 실데이터가 잡히면 base 는 그 종가로 갱신되고, 이후 랜덤워크도 그 base 를 쓴다.
func TestPollingSource_BasePriceAdoptsLatestClose(t *testing.T) {
	body := `{"stockCode":"005930","points":[{"baseDate":"2026-08-20","closePrice":70000},{"baseDate":"2026-08-21","closePrice":71500}]}`
	srv := seriesServer(t, body, http.StatusOK, nil)

	sim := NewSimulatedSource(42)
	p := NewPollingSource(sim, srv.URL, time.Minute, quietLogger())

	got := p.BasePrice("005930", 50000)
	if got != 71500 {
		t.Fatalf("가장 마지막 포인트(오름차순 시리즈의 끝)를 써야 한다: got %v", got)
	}
	// SetBase 까지 내려갔는지 — 폴링이 끝나도 워크가 옛 base 를 쓰면 의미가 없다.
	if base := sim.BasePrice("005930", 50000); base != 71500 {
		t.Fatalf("simulated base 가 갱신되지 않았다: %v", base)
	}
}

// 폴링은 종목별 rate limit 이 걸린다 — 매 틱마다 market-service 를 때리면 안 된다.
func TestPollingSource_RateLimitsPerCode(t *testing.T) {
	var hits int32
	body := `{"points":[{"baseDate":"2026-08-21","closePrice":71500}]}`
	srv := seriesServer(t, body, http.StatusOK, &hits)

	p := NewPollingSource(NewSimulatedSource(42), srv.URL, time.Hour, quietLogger())

	for i := 0; i < 5; i++ {
		p.BasePrice("005930", 50000)
	}
	if got := atomic.LoadInt32(&hits); got != 1 {
		t.Fatalf("pollInterval 안에서는 1회만 조회해야 한다: got %d", got)
	}

	// 다른 종목은 자기 창을 따로 가진다.
	p.BasePrice("000660", 50000)
	if got := atomic.LoadInt32(&hits); got != 2 {
		t.Fatalf("종목별로 창이 분리돼야 한다: got %d", got)
	}
}

// pollInterval 이 0 이면 매번 새로 조회한다(테스트/디버깅용 설정).
func TestPollingSource_ZeroIntervalPollsEveryTime(t *testing.T) {
	var hits int32
	srv := seriesServer(t, `{"points":[{"closePrice":71500}]}`, http.StatusOK, &hits)

	p := NewPollingSource(NewSimulatedSource(42), srv.URL, 0, quietLogger())
	p.BasePrice("005930", 50000)
	p.BasePrice("005930", 50000)

	if got := atomic.LoadInt32(&hits); got != 2 {
		t.Fatalf("interval 0 이면 매번 조회해야 한다: got %d", got)
	}
}

// 폴링이 실패해도 스트림은 죽지 않는다 — 어떤 실패든 fallback 으로 내려앉아야 한다.
func TestPollingSource_FallsBackOnEveryFailureMode(t *testing.T) {
	const fallback = 50000.0

	cases := []struct {
		name   string
		body   string
		status int
	}{
		{"non-200", `{}`, http.StatusInternalServerError},
		{"broken json", `{"points":[`, http.StatusOK},
		{"empty series", `{"points":[]}`, http.StatusOK},
		{"non-positive close", `{"points":[{"closePrice":0}]}`, http.StatusOK},
		{"negative close", `{"points":[{"closePrice":-10}]}`, http.StatusOK},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			srv := seriesServer(t, tc.body, tc.status, nil)
			p := NewPollingSource(NewSimulatedSource(42), srv.URL, time.Minute, quietLogger())

			if got := p.BasePrice("005930", fallback); got != fallback {
				t.Fatalf("실패 시 fallback 으로 내려앉아야 한다: got %v", got)
			}
		})
	}
}

func TestPollingSource_FallsBackWhenMarketServiceUnreachable(t *testing.T) {
	// 닫힌 포트 — 전송 계층 실패 경로.
	p := NewPollingSource(NewSimulatedSource(42), "http://127.0.0.1:1", time.Minute, quietLogger())

	if got := p.BasePrice("005930", 50000); got != 50000 {
		t.Fatalf("연결 실패 시 fallback 이어야 한다: got %v", got)
	}
}

func TestPollingSource_FallsBackWhenURLIsUnbuildable(t *testing.T) {
	// 제어문자가 섞인 baseURL 은 http.NewRequestWithContext 단계에서 깨진다.
	p := NewPollingSource(NewSimulatedSource(42), "http://bad\x7fhost", time.Minute, quietLogger())

	if got := p.BasePrice("005930", 50000); got != 50000 {
		t.Fatalf("요청 생성 실패 시 fallback 이어야 한다: got %v", got)
	}
}

// PollingSource 는 QuoteSource 를 만족해야 Hub 에 꽂힌다.
var _ QuoteSource = (*PollingSource)(nil)
