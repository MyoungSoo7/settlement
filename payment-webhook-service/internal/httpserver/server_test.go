package httpserver

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/myoungsoo7/settlement/payment-webhook-service/internal/idempotency"
	"github.com/myoungsoo7/settlement/payment-webhook-service/internal/publisher"
	"github.com/myoungsoo7/settlement/payment-webhook-service/internal/webhook"
)

// recordingPublisher counts accepted events without needing a broker.
type recordingPublisher struct{ published int }

func (p *recordingPublisher) Publish(context.Context, publisher.PaymentConfirmedEvent) error {
	p.published++
	return nil
}
func (p *recordingPublisher) Close() error { return nil }

const secret = "s3cr3t"

func newTestRouter() (http.Handler, *recordingPublisher) {
	pub := &recordingPublisher{}
	handler := webhook.NewHandler(secret, idempotency.NewMemoryStore(time.Hour), pub, nil)
	return NewRouter(handler), pub
}

func TestRouter_HealthzReportsUp(t *testing.T) {
	router, _ := newTestRouter()

	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/healthz", nil))

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	if got := rec.Header().Get("Content-Type"); got != "application/json" {
		t.Fatalf("healthz must be JSON, got %q", got)
	}
	if !strings.Contains(rec.Body.String(), `"status":"UP"`) {
		t.Fatalf("unexpected healthz body: %s", rec.Body.String())
	}
}

func TestRouter_WebhookRouteIsWired(t *testing.T) {
	router, pub := newTestRouter()

	body := `{"eventType":"PAYMENT_STATUS_CHANGED","data":{"paymentKey":"pk_abc","orderId":"order_1","status":"DONE","totalAmount":15000}}`
	req := httptest.NewRequest(http.MethodPost, "/webhooks/toss", strings.NewReader(body))
	req.Header.Set(webhook.SignatureHeader, webhook.ComputeSignature(secret, []byte(body)))

	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200 through the router, got %d (%s)", rec.Code, rec.Body.String())
	}
	if pub.published != 1 {
		t.Fatalf("router must reach the handler; published=%d", pub.published)
	}
}

func TestRouter_UnknownPathIs404(t *testing.T) {
	router, _ := newTestRouter()

	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/nope", nil))

	if rec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 for an unrouted path, got %d", rec.Code)
	}
}

func TestRouter_WebhookPathRejectsGet(t *testing.T) {
	router, _ := newTestRouter()

	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/webhooks/toss", nil))

	// The mux registers the route as POST-only, so a GET never reaches the
	// handler — either way it must not be treated as a webhook delivery.
	if rec.Code == http.StatusOK {
		t.Fatalf("GET on the webhook path must not succeed, got %d", rec.Code)
	}
}
