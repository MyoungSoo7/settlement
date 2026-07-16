// Command server runs the payment-webhook-service: it ingests Toss payment
// webhooks (HMAC-verified, idempotent) and publishes normalized domain events.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/myoungsoo7/settlement/payment-webhook-service/internal/httpserver"
	"github.com/myoungsoo7/settlement/payment-webhook-service/internal/idempotency"
	"github.com/myoungsoo7/settlement/payment-webhook-service/internal/publisher"
	"github.com/myoungsoo7/settlement/payment-webhook-service/internal/webhook"
)

const (
	defaultPort       = "8111"
	idempotencyTTL    = 24 * time.Hour
	shutdownGracePd   = 10 * time.Second
	readHeaderTimeout = 5 * time.Second
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(logger)

	port := getenv("PAYMENT_WEBHOOK_PORT", defaultPort)
	secret := os.Getenv("TOSS_WEBHOOK_SECRET")
	if secret == "" {
		logger.Warn("TOSS_WEBHOOK_SECRET is empty; all webhook signatures will be rejected")
	}

	// Choose publisher: Kafka when brokers configured, else Log (no broker).
	var pub publisher.EventPublisher
	if brokers := parseBrokers(os.Getenv("KAFKA_BROKERS")); len(brokers) > 0 {
		pub = publisher.NewKafkaPublisher(brokers, logger)
		logger.Info("using kafka publisher", "brokers", brokers, "topic", publisher.Topic)
	} else {
		pub = publisher.NewLogPublisher(logger)
		logger.Info("using log publisher (no KAFKA_BROKERS set)", "topic", publisher.Topic)
	}
	defer func() { _ = pub.Close() }()

	store := idempotency.NewMemoryStore(idempotencyTTL)
	handler := webhook.NewHandler(secret, store, pub, logger)
	router := httpserver.NewRouter(handler)

	srv := &http.Server{
		Addr:              ":" + port,
		Handler:           router,
		ReadHeaderTimeout: readHeaderTimeout,
	}

	// Run server, shutdown gracefully on SIGINT/SIGTERM.
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	go func() {
		logger.Info("payment-webhook-service listening", "addr", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("server error", "err", err)
			stop()
		}
	}()

	<-ctx.Done()
	logger.Info("shutdown signal received, draining connections")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), shutdownGracePd)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		logger.Error("graceful shutdown failed", "err", err)
		os.Exit(1)
	}
	logger.Info("shutdown complete")
}

func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func parseBrokers(raw string) []string {
	if strings.TrimSpace(raw) == "" {
		return nil
	}
	parts := strings.Split(raw, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}
