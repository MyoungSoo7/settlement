package httpapi

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/myoungsoo7/settlement/market-stream-service/internal/hub"
	"github.com/myoungsoo7/settlement/market-stream-service/internal/quote"
)

// nonFlushingWriter 는 Flusher 를 구현하지 않는 ResponseWriter 다.
// SSE 는 flush 없이는 프록시 뒤에서 한 글자도 나가지 않으므로, 이 경우 스트림을 열어 두고
// 침묵하는 대신 즉시 실패해야 한다.
type nonFlushingWriter struct {
	header http.Header
	status int
	body   []byte
}

func (w *nonFlushingWriter) Header() http.Header {
	if w.header == nil {
		w.header = make(http.Header)
	}
	return w.header
}
func (w *nonFlushingWriter) Write(p []byte) (int, error) {
	w.body = append(w.body, p...)
	return len(p), nil
}
func (w *nonFlushingWriter) WriteHeader(status int) { w.status = status }

func TestNewServer_NilLoggerFallsBack(t *testing.T) {
	h := hub.New(quote.NewSimulatedSource(1), time.Second, 1, slog.New(slog.NewTextHandler(io.Discard, nil)))
	s := NewServer(h, nil)
	if s.log == nil {
		t.Fatal("nil 로거는 slog.Default 로 대체돼야 한다")
	}
}

func TestSSE_RejectsNonFlushableWriter(t *testing.T) {
	s := testServer()
	req := httptest.NewRequest(http.MethodGet, "/stream/005930", nil)
	req.SetPathValue("stockCode", "005930")

	w := &nonFlushingWriter{}
	s.handleSSE(w, req)

	if w.status != http.StatusInternalServerError {
		t.Fatalf("flush 불가 writer 는 500 이어야 한다: got %d", w.status)
	}
	if got := s.hub.SubscriberCount("005930"); got != 0 {
		t.Fatalf("실패한 요청이 구독을 남겼다: %d", got)
	}
}

// 클라이언트가 끊으면 구독이 정리돼야 한다 — 안 그러면 종목 루프가 영원히 남는다.
func TestSSE_UnsubscribesOnClientDisconnect(t *testing.T) {
	s := testServer()
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/stream/005930", nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("get: %v", err)
	}

	// 첫 바이트(": connected")까지 받아 구독이 성립한 것을 확인한다.
	buf := make([]byte, 1)
	if _, err := resp.Body.Read(buf); err != nil {
		t.Fatalf("스트림 개시 실패: %v", err)
	}
	if s.hub.SubscriberCount("005930") != 1 {
		t.Fatalf("구독이 생기지 않았다: %d", s.hub.SubscriberCount("005930"))
	}

	_ = resp.Body.Close()

	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if s.hub.SubscriberCount("005930") == 0 {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("연결이 끊겼는데 구독이 남아 있다: %d", s.hub.SubscriberCount("005930"))
}

// 라우팅되지 않은 경로는 404 — 스트림 경로 오타가 조용히 200 으로 넘어가면 안 된다.
func TestHandler_UnknownPathIs404(t *testing.T) {
	srv := httptest.NewServer(testServer().Handler())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/nope")
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", resp.StatusCode)
	}
}
