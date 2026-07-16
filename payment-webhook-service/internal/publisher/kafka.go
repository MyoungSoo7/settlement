package publisher

import (
	"context"
	"encoding/json"
	"log/slog"

	"github.com/segmentio/kafka-go"
)

// KafkaPublisher publishes normalized events to Kafka via segmentio/kafka-go.
// It is used instead of LogPublisher when KAFKA_BROKERS is configured.
type KafkaPublisher struct {
	writer *kafka.Writer
	logger *slog.Logger
}

// NewKafkaPublisher constructs a KafkaPublisher writing to Topic across the
// given broker addresses. A nil logger falls back to slog.Default.
func NewKafkaPublisher(brokers []string, logger *slog.Logger) *KafkaPublisher {
	if logger == nil {
		logger = slog.Default()
	}
	w := &kafka.Writer{
		Addr:                   kafka.TCP(brokers...),
		Topic:                  Topic,
		Balancer:               &kafka.Hash{}, // hash on Key => per-payment ordering
		AllowAutoTopicCreation: true,
		RequiredAcks:           kafka.RequireAll,
	}
	return &KafkaPublisher{writer: w, logger: logger}
}

// Publish marshals the event and writes it keyed by paymentKey.
func (p *KafkaPublisher) Publish(ctx context.Context, event PaymentConfirmedEvent) error {
	payload, err := json.Marshal(event)
	if err != nil {
		return err
	}
	msg := kafka.Message{
		Key:   []byte(event.Key()),
		Value: payload,
	}
	if err := p.writer.WriteMessages(ctx, msg); err != nil {
		p.logger.ErrorContext(ctx, "kafka publish failed", "topic", Topic, "key", event.Key(), "err", err)
		return err
	}
	return nil
}

// Close flushes and closes the underlying Kafka writer.
func (p *KafkaPublisher) Close() error { return p.writer.Close() }
