package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"nhooyr.io/websocket"
)

// WS 스트림은 SSE 와 같은 계약이어야 한다 — 연결 1개 = 구독 1개, 끊기면 구독도 사라진다.
func TestWS_EmitsTickAndUnsubscribesOnClose(t *testing.T) {
	s := testServer()
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	wsURL := "ws" + srv.URL[len("http"):] + "/ws/005930"
	c, _, err := websocket.Dial(ctx, wsURL, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}

	_, data, err := c.Read(ctx)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	var tick map[string]any
	if err := json.Unmarshal(data, &tick); err != nil {
		t.Fatalf("틱은 JSON 이어야 한다: %v (%s)", err, data)
	}
	if tick["stockCode"] != "005930" {
		t.Fatalf("stockCode 가 요청 종목과 달라졌다: %v", tick["stockCode"])
	}
	if tick["source"] != "SAMPLE" {
		t.Fatalf("합성 틱은 SAMPLE 로 표기돼야 한다: %v", tick["source"])
	}

	_ = c.Close(websocket.StatusNormalClosure, "done")

	// 끊긴 클라이언트의 구독이 남으면 종목 루프가 영원히 돌아간다(고루틴 누수).
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if s.hub.SubscriberCount("005930") == 0 {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("클라이언트가 끊겼는데 구독이 남아 있다: %d", s.hub.SubscriberCount("005930"))
}

func TestWS_BlankCodeRejected(t *testing.T) {
	s := testServer()
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/ws/%20", nil)
	req.SetPathValue("stockCode", "  ")

	s.handleWS(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("공백 종목코드는 400 이어야 한다: got %d", rec.Code)
	}
}

// 업그레이드 헤더 없는 평범한 GET 은 Accept 단계에서 거절된다 — 이때도 구독이 생기면 안 된다.
func TestWS_PlainGetIsRejectedWithoutSubscribing(t *testing.T) {
	s := testServer()
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/ws/005930")
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusSwitchingProtocols {
		t.Fatal("업그레이드 없는 GET 이 WS 로 승격됐다")
	}
	if got := s.hub.SubscriberCount("005930"); got != 0 {
		t.Fatalf("거절된 요청이 구독을 남겼다: %d", got)
	}
}

// Hub 가 내려가면 채널이 닫히고, 핸들러는 정상 종료 코드로 연결을 닫아야 한다.
func TestWS_ClosesWhenHubShutsDown(t *testing.T) {
	s := testServer()
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	wsURL := "ws" + srv.URL[len("http"):] + "/ws/005930"
	c, _, err := websocket.Dial(ctx, wsURL, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer c.Close(websocket.StatusInternalError, "cleanup")

	if _, _, err := c.Read(ctx); err != nil {
		t.Fatalf("첫 틱 수신 실패: %v", err)
	}

	s.hub.Shutdown()

	// 서버가 닫으면 클라이언트의 다음 Read 는 에러로 끝난다.
	if _, _, err := c.Read(ctx); err == nil {
		t.Fatal("Hub 종료 후에도 연결이 열려 있다")
	}
}
