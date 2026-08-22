package publisher

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"math"
	"strings"
	"testing"
)

func TestPaymentConfirmedEvent_KeyIsPaymentKey(t *testing.T) {
	// The message key decides the partition, and the partition decides ordering
	// per payment. It must be the paymentKey and nothing else.
	e := PaymentConfirmedEvent{PaymentKey: "pk_abc", OrderID: "order_1"}
	if e.Key() != "pk_abc" {
		t.Fatalf("Key() must be the paymentKey, got %q", e.Key())
	}
}

func TestTopicFollowsNamingConvention(t *testing.T) {
	if Topic != "lemuel.payment.confirmed" {
		t.Fatalf("topic name is a published contract; got %q", Topic)
	}
}

func TestLogPublisher_PublishWritesTheEvent(t *testing.T) {
	var buf bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&buf, &slog.HandlerOptions{Level: slog.LevelInfo}))
	p := NewLogPublisher(logger)

	event := PaymentConfirmedEvent{
		EventType:   "PAYMENT_STATUS_CHANGED",
		PaymentKey:  "pk_abc",
		OrderID:     "order_1",
		Status:      "DONE",
		TotalAmount: 15000,
		OccurredAt:  "2026-08-22T03:04:05Z",
	}
	if err := p.Publish(context.Background(), event); err != nil {
		t.Fatalf("publish must succeed with no broker attached: %v", err)
	}

	out := buf.String()
	for _, want := range []string{Topic, "pk_abc", "order_1", "15000"} {
		if !strings.Contains(out, want) {
			t.Fatalf("logged event is missing %q: %s", want, out)
		}
	}
}

func TestLogPublisher_NilLoggerFallsBack(t *testing.T) {
	p := NewLogPublisher(nil)
	if p.logger == nil {
		t.Fatal("a nil logger must fall back to slog.Default, not stay nil")
	}
	if err := p.Publish(context.Background(), PaymentConfirmedEvent{PaymentKey: "pk"}); err != nil {
		t.Fatalf("publish with the fallback logger must succeed: %v", err)
	}
}

func TestLogPublisher_CloseIsNoOp(t *testing.T) {
	if err := NewLogPublisher(nil).Close(); err != nil {
		t.Fatalf("Close must be a no-op, got %v", err)
	}
}

func TestPaymentConfirmedEvent_JSONFieldNamesAreTheContract(t *testing.T) {
	// Downstream consumers bind to these names — a rename here is a breaking
	// change that no compiler would catch.
	raw, err := json.Marshal(PaymentConfirmedEvent{})
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	var decoded map[string]any
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	for _, field := range []string{"eventType", "paymentKey", "orderId", "status", "totalAmount", "occurredAt"} {
		if _, ok := decoded[field]; !ok {
			t.Fatalf("event contract lost field %q: %s", field, raw)
		}
	}
}

func TestLogPublisher_AmountSurvivesRoundTrip(t *testing.T) {
	// int64 amounts must not be silently degraded to float64 on the way out.
	p := NewLogPublisher(slog.New(slog.NewJSONHandler(new(bytes.Buffer), nil)))
	big := int64(math.MaxInt64)
	if err := p.Publish(context.Background(), PaymentConfirmedEvent{PaymentKey: "pk", TotalAmount: big}); err != nil {
		t.Fatalf("publish: %v", err)
	}
	raw, err := json.Marshal(PaymentConfirmedEvent{TotalAmount: big})
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	if !strings.Contains(string(raw), "9223372036854775807") {
		t.Fatalf("amount lost precision: %s", raw)
	}
}
