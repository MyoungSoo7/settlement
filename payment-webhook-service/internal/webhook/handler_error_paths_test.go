package webhook

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/myoungsoo7/settlement/payment-webhook-service/internal/idempotency"
	"github.com/myoungsoo7/settlement/payment-webhook-service/internal/publisher"
)

// erroringStore fails MarkIfNew so the "idempotency store is down" branch can be
// exercised. A down store must never let the event through silently.
type erroringStore struct{}

func (erroringStore) MarkIfNew(context.Context, string) (bool, error) {
	return false, errors.New("store unavailable")
}

// erroringPublisher fails Publish so the "broker rejected the write" branch can
// be exercised.
type erroringPublisher struct{}

func (erroringPublisher) Publish(context.Context, publisher.PaymentConfirmedEvent) error {
	return errors.New("broker unavailable")
}
func (erroringPublisher) Close() error { return nil }

// erroringBody fails on Read so the "cannot read body" branch can be exercised.
type erroringBody struct{}

func (erroringBody) Read([]byte) (int, error) { return 0, errors.New("connection reset") }
func (erroringBody) Close() error             { return nil }

func TestHandler_RejectsNonPost(t *testing.T) {
	h, pub := newTestHandler("s3cr3t")

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/webhooks/toss", nil))

	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("expected 405, got %d", rec.Code)
	}
	if pub.count() != 0 {
		t.Fatal("must not publish for a non-POST request")
	}
}

func TestHandler_UnreadableBody(t *testing.T) {
	h, pub := newTestHandler("s3cr3t")

	req := httptest.NewRequest(http.MethodPost, "/webhooks/toss", strings.NewReader(""))
	req.Body = erroringBody{}

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 when the body cannot be read, got %d", rec.Code)
	}
	if pub.count() != 0 {
		t.Fatal("must not publish when the body could not be read")
	}
}

func TestHandler_MissingSignatureHeaderRejected(t *testing.T) {
	h, pub := newTestHandler("s3cr3t")

	// No signature header at all — the header is the only thing standing between
	// the public internet and a published payment event.
	req := httptest.NewRequest(http.MethodPost, "/webhooks/toss", strings.NewReader(sampleBody))

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 without a signature header, got %d", rec.Code)
	}
	if pub.count() != 0 {
		t.Fatal("must not publish without a signature")
	}
}

func TestHandler_InvalidJSON(t *testing.T) {
	const secret = "s3cr3t"
	h, pub := newTestHandler(secret)

	// Correctly signed, but not JSON — signature passes, decode must fail.
	body := `{"eventType":`
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, signedRequest(secret, body))

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for malformed JSON, got %d", rec.Code)
	}
	if pub.count() != 0 {
		t.Fatal("must not publish malformed JSON")
	}
}

func TestHandler_MissingPaymentKey(t *testing.T) {
	const secret = "s3cr3t"
	h, pub := newTestHandler(secret)

	// paymentKey is the dedupe key and the message key — without it there is no
	// idempotency and no ordering, so the request must be refused.
	body := `{"eventType":"PAYMENT_STATUS_CHANGED","data":{"orderId":"order_1"}}`
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, signedRequest(secret, body))

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 without paymentKey, got %d", rec.Code)
	}
	if pub.count() != 0 {
		t.Fatal("must not publish without paymentKey")
	}
}

func TestHandler_StoreFailureIsNotAcked(t *testing.T) {
	const secret = "s3cr3t"
	pub := &fakePublisher{}
	h := NewHandler(secret, erroringStore{}, pub, nil)

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, signedRequest(secret, sampleBody))

	// 500 (not 200) so Toss retries — acking a webhook we could not dedupe would
	// silently drop it.
	if rec.Code != http.StatusInternalServerError {
		t.Fatalf("expected 500 when the idempotency store fails, got %d", rec.Code)
	}
	if pub.count() != 0 {
		t.Fatal("must not publish when the dedupe check did not complete")
	}
}

func TestHandler_PublishFailureIsNotAcked(t *testing.T) {
	const secret = "s3cr3t"
	h := NewHandler(secret, idempotency.NewMemoryStore(time.Hour), erroringPublisher{}, nil)

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, signedRequest(secret, sampleBody))

	if rec.Code != http.StatusInternalServerError {
		t.Fatalf("expected 500 when publishing fails, got %d", rec.Code)
	}
}

func TestHandler_OccurredAtUsesInjectedClock(t *testing.T) {
	const secret = "s3cr3t"
	h, pub := newTestHandler(secret)
	fixed := time.Date(2026, 8, 22, 3, 4, 5, 0, time.UTC)
	h.now = func() time.Time { return fixed }

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, signedRequest(secret, sampleBody))

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	if got := pub.events[0].OccurredAt; got != "2026-08-22T03:04:05Z" {
		t.Fatalf("OccurredAt must be the injected clock in UTC RFC3339, got %q", got)
	}
}

func TestHandler_BodyIsCappedAtOneMiB(t *testing.T) {
	const secret = "s3cr3t"
	h, pub := newTestHandler(secret)

	// A body larger than the cap gets truncated before hashing, so its signature
	// can never match — the cap is a memory guard, not a silent accept.
	huge := strings.Repeat("a", maxBodyBytes+10)
	req := httptest.NewRequest(http.MethodPost, "/webhooks/toss", strings.NewReader(huge))
	req.Header.Set(SignatureHeader, ComputeSignature(secret, []byte(huge)))

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for an over-sized body, got %d", rec.Code)
	}
	if pub.count() != 0 {
		t.Fatal("must not publish an over-sized body")
	}
}

func TestNewHandler_NilLoggerFallsBack(t *testing.T) {
	h := NewHandler("s", idempotency.NewMemoryStore(time.Hour), &fakePublisher{}, nil)
	if h.logger == nil {
		t.Fatal("a nil logger must fall back to slog.Default, not stay nil")
	}
}

// compile-time guard: the fakes really do satisfy the ports they stand in for.
var (
	_ io.ReadCloser                = erroringBody{}
	_ publisher.EventPublisher     = erroringPublisher{}
	_ idempotency.IdempotencyStore = erroringStore{}
)
