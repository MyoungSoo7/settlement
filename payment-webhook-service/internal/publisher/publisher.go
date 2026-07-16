// Package publisher defines the EventPublisher abstraction used to emit
// normalized domain events downstream, plus a LogPublisher default impl
// so the service builds and runs with no broker attached.
package publisher

import (
	"context"
	"encoding/json"
	"log/slog"
)

// Topic is the Kafka topic normalized payment events are published to.
// Follows the repo convention lemuel.<domain>.<event>.
const Topic = "lemuel.payment.confirmed"

// PaymentConfirmedEvent is the normalized domain event emitted after a Toss
// payment webhook is accepted. It is deliberately decoupled from the raw
// Toss webhook shape so downstream consumers depend on our contract, not Toss'.
type PaymentConfirmedEvent struct {
	EventType   string `json:"eventType"`
	PaymentKey  string `json:"paymentKey"`
	OrderID     string `json:"orderId"`
	Status      string `json:"status"`
	TotalAmount int64  `json:"totalAmount"`
	OccurredAt  string `json:"occurredAt"`
}

// Key returns the partition/message key used for ordering per payment.
func (e PaymentConfirmedEvent) Key() string { return e.PaymentKey }

// EventPublisher publishes a normalized payment event. Implementations must be
// safe for concurrent use by multiple goroutines.
type EventPublisher interface {
	Publish(ctx context.Context, event PaymentConfirmedEvent) error
	Close() error
}

// LogPublisher is the default EventPublisher. It marshals the event and logs it,
// letting the service run end-to-end with no Kafka broker present (MVP / local).
type LogPublisher struct {
	logger *slog.Logger
}

// NewLogPublisher builds a LogPublisher. A nil logger falls back to slog.Default.
func NewLogPublisher(logger *slog.Logger) *LogPublisher {
	if logger == nil {
		logger = slog.Default()
	}
	return &LogPublisher{logger: logger}
}

// Publish serializes the event and writes it to the log.
func (p *LogPublisher) Publish(ctx context.Context, event PaymentConfirmedEvent) error {
	payload, err := json.Marshal(event)
	if err != nil {
		return err
	}
	p.logger.InfoContext(ctx, "event published (log publisher)",
		"topic", Topic,
		"key", event.Key(),
		"event", json.RawMessage(payload),
	)
	return nil
}

// Close is a no-op for the LogPublisher.
func (p *LogPublisher) Close() error { return nil }
