package webhook

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/myoungsoo7/settlement/payment-webhook-service/internal/idempotency"
	"github.com/myoungsoo7/settlement/payment-webhook-service/internal/publisher"
)

// fakePublisher records published events for assertions.
type fakePublisher struct {
	mu     sync.Mutex
	events []publisher.PaymentConfirmedEvent
}

func (f *fakePublisher) Publish(_ context.Context, e publisher.PaymentConfirmedEvent) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.events = append(f.events, e)
	return nil
}
func (f *fakePublisher) Close() error { return nil }
func (f *fakePublisher) count() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.events)
}

const sampleBody = `{"eventType":"PAYMENT_STATUS_CHANGED","data":{"paymentKey":"pk_abc","orderId":"order_1","status":"DONE","totalAmount":15000}}`

func newTestHandler(secret string) (*Handler, *fakePublisher) {
	pub := &fakePublisher{}
	store := idempotency.NewMemoryStore(time.Hour)
	return NewHandler(secret, store, pub, nil), pub
}

func signedRequest(secret, body string) *http.Request {
	req := httptest.NewRequest(http.MethodPost, "/webhooks/toss", strings.NewReader(body))
	req.Header.Set(SignatureHeader, ComputeSignature(secret, []byte(body)))
	return req
}

func TestHandler_HappyPath(t *testing.T) {
	const secret = "s3cr3t"
	h, pub := newTestHandler(secret)

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, signedRequest(secret, sampleBody))

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d (body: %s)", rec.Code, rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), `"received":true`) ||
		!strings.Contains(rec.Body.String(), `"duplicate":false`) {
		t.Fatalf("unexpected body: %s", rec.Body.String())
	}
	if pub.count() != 1 {
		t.Fatalf("expected exactly 1 published event, got %d", pub.count())
	}
	got := pub.events[0]
	if got.PaymentKey != "pk_abc" || got.OrderID != "order_1" || got.TotalAmount != 15000 {
		t.Fatalf("event not normalized correctly: %+v", got)
	}
}

func TestHandler_BadSignature(t *testing.T) {
	h, pub := newTestHandler("s3cr3t")

	req := httptest.NewRequest(http.MethodPost, "/webhooks/toss", strings.NewReader(sampleBody))
	req.Header.Set(SignatureHeader, "bogus")

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", rec.Code)
	}
	if pub.count() != 0 {
		t.Fatal("must not publish on signature failure")
	}
}

func TestHandler_TamperedBodyRejected(t *testing.T) {
	const secret = "s3cr3t"
	h, pub := newTestHandler(secret)

	// Sign the original body, then send a different body.
	req := httptest.NewRequest(http.MethodPost, "/webhooks/toss", strings.NewReader(`{"data":{"paymentKey":"evil"}}`))
	req.Header.Set(SignatureHeader, ComputeSignature(secret, []byte(sampleBody)))

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for tampered body, got %d", rec.Code)
	}
	if pub.count() != 0 {
		t.Fatal("must not publish on tampered body")
	}
}

func TestHandler_Idempotency_DuplicatePublishedOnce(t *testing.T) {
	const secret = "s3cr3t"
	h, pub := newTestHandler(secret)

	// First delivery.
	rec1 := httptest.NewRecorder()
	h.ServeHTTP(rec1, signedRequest(secret, sampleBody))
	if rec1.Code != http.StatusOK {
		t.Fatalf("first: expected 200, got %d", rec1.Code)
	}

	// Duplicate delivery (same paymentKey + eventType).
	rec2 := httptest.NewRecorder()
	h.ServeHTTP(rec2, signedRequest(secret, sampleBody))
	if rec2.Code != http.StatusOK {
		t.Fatalf("duplicate: expected 200, got %d", rec2.Code)
	}
	if !strings.Contains(rec2.Body.String(), `"duplicate":true`) {
		t.Fatalf("duplicate should be flagged: %s", rec2.Body.String())
	}
	if pub.count() != 1 {
		t.Fatalf("publisher must be called exactly once, got %d", pub.count())
	}
}
