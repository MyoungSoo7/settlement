package publisher

import (
	"bytes"
	"context"
	"log/slog"
	"testing"

	"github.com/segmentio/kafka-go"
)

func TestNewKafkaPublisher_WriterSettingsPreserveOrdering(t *testing.T) {
	p := NewKafkaPublisher([]string{"broker-a:9092", "broker-b:9092"}, nil)

	if p.writer.Topic != Topic {
		t.Fatalf("writer must target %q, got %q", Topic, p.writer.Topic)
	}
	// Hash balancer on the message key is what keeps per-payment ordering; a
	// round-robin balancer would scatter one payment across partitions.
	if _, ok := p.writer.Balancer.(*kafka.Hash); !ok {
		t.Fatalf("balancer must hash on the key, got %T", p.writer.Balancer)
	}
	if p.writer.RequiredAcks != kafka.RequireAll {
		t.Fatalf("acks must be all so a published payment survives a broker loss, got %v", p.writer.RequiredAcks)
	}
	if p.logger == nil {
		t.Fatal("a nil logger must fall back to slog.Default, not stay nil")
	}
}

func TestKafkaPublisher_PublishReturnsBrokerError(t *testing.T) {
	var buf bytes.Buffer
	p := NewKafkaPublisher([]string{"127.0.0.1:1"}, slog.New(slog.NewJSONHandler(&buf, nil)))
	t.Cleanup(func() { _ = p.Close() })

	// Cancelled context => the write fails without waiting on a dial timeout.
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	err := p.Publish(ctx, PaymentConfirmedEvent{PaymentKey: "pk_abc"})
	if err == nil {
		t.Fatal("a failed broker write must surface as an error, not be swallowed")
	}
	if !bytes.Contains(buf.Bytes(), []byte("kafka publish failed")) {
		t.Fatalf("the failure must be logged for on-call, got: %s", buf.String())
	}
}

func TestKafkaPublisher_CloseReleasesTheWriter(t *testing.T) {
	p := NewKafkaPublisher([]string{"127.0.0.1:1"}, nil)
	if err := p.Close(); err != nil {
		t.Fatalf("Close must release the writer cleanly, got %v", err)
	}
}

// compile-time guard: both publishers really are interchangeable behind the port.
var (
	_ EventPublisher = (*LogPublisher)(nil)
	_ EventPublisher = (*KafkaPublisher)(nil)
)
