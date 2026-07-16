package httpapi

import (
	"bufio"
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/myoungsoo7/settlement/market-stream-service/internal/hub"
	"github.com/myoungsoo7/settlement/market-stream-service/internal/quote"
)

func testServer() *Server {
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	h := hub.New(quote.NewSimulatedSource(5), 10*time.Millisecond, 8, log)
	return NewServer(h, log)
}

func TestHealthz(t *testing.T) {
	srv := httptest.NewServer(testServer().Handler())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/healthz")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status: got %d want 200", resp.StatusCode)
	}
	body, _ := io.ReadAll(resp.Body)
	if !strings.Contains(string(body), `"status":"UP"`) {
		t.Fatalf("unexpected body: %s", body)
	}
}

func TestSSE_EmitsTick(t *testing.T) {
	srv := httptest.NewServer(testServer().Handler())
	defer srv.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, srv.URL+"/stream/005930", nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()

	if ct := resp.Header.Get("Content-Type"); ct != "text/event-stream" {
		t.Fatalf("content-type: got %q", ct)
	}

	reader := bufio.NewReader(resp.Body)
	sawEvent := false
	sawData := false
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		line, err := reader.ReadString('\n')
		if err != nil {
			break
		}
		line = strings.TrimSpace(line)
		if line == "event: tick" {
			sawEvent = true
		}
		if strings.HasPrefix(line, "data: ") {
			sawData = true
			if !strings.Contains(line, `"stockCode":"005930"`) {
				t.Fatalf("data missing stockCode: %s", line)
			}
			if !strings.Contains(line, `"price"`) || !strings.Contains(line, `"ts"`) {
				t.Fatalf("data missing fields: %s", line)
			}
		}
		if sawEvent && sawData {
			break
		}
	}
	if !sawEvent || !sawData {
		t.Fatalf("did not observe a tick event: sawEvent=%v sawData=%v", sawEvent, sawData)
	}
}

func TestSSE_MissingCode(t *testing.T) {
	// Path routing requires a segment, so hitting /stream/ (empty) 404s at mux;
	// verify the handler rejects a whitespace code via a direct recorder call.
	srv := testServer()
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/stream/%20", nil)
	req.SetPathValue("stockCode", " ")
	srv.handleSSE(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for blank code, got %d", rec.Code)
	}
}
