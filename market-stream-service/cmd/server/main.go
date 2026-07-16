// Command server runs the market-stream-service: real-time stock price
// streaming (SSE + WebSocket) for the investment / CEO dashboards.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/myoungsoo7/settlement/market-stream-service/internal/httpapi"
	"github.com/myoungsoo7/settlement/market-stream-service/internal/hub"
	"github.com/myoungsoo7/settlement/market-stream-service/internal/quote"
)

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(log)

	cfg := loadConfig(log)

	source := buildSource(cfg, log)
	log.Info("quote source selected", "source", source.Name())

	h := hub.New(source, cfg.tickInterval, cfg.subBuffer, log)

	srv := &http.Server{
		Addr:              ":" + cfg.port,
		Handler:           httpapi.NewServer(h, log).Handler(),
		ReadHeaderTimeout: 5 * time.Second,
	}

	// Run the HTTP server.
	serverErr := make(chan error, 1)
	go func() {
		log.Info("market-stream-service listening", "addr", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			serverErr <- err
		}
	}()

	// Wait for signal or fatal server error.
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	select {
	case err := <-serverErr:
		log.Error("http server failed", "err", err)
	case <-ctx.Done():
		log.Info("shutdown signal received")
	}

	// Graceful shutdown.
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		log.Error("graceful shutdown failed", "err", err)
	}
	h.Shutdown()
	log.Info("market-stream-service stopped")
}

type config struct {
	port         string
	tickInterval time.Duration
	subBuffer    int
	seed         int64
	sourceKind   string // "simulated" | "polling"
	marketBase   string
	pollInterval time.Duration
}

func loadConfig(log *slog.Logger) config {
	c := config{
		port:         getenv("MARKET_STREAM_PORT", "8110"),
		tickInterval: getDuration("MARKET_STREAM_TICK_INTERVAL", time.Second, log),
		subBuffer:    getInt("MARKET_STREAM_SUB_BUFFER", 16, log),
		seed:         getInt64("MARKET_STREAM_SEED", time.Now().UnixNano(), log),
		sourceKind:   getenv("MARKET_STREAM_SOURCE", "simulated"),
		marketBase:   getenv("MARKET_BASE_URL", "http://market-service:8080"),
		pollInterval: getDuration("MARKET_STREAM_POLL_INTERVAL", 60*time.Second, log),
	}
	return c
}

func buildSource(cfg config, log *slog.Logger) quote.QuoteSource {
	sim := quote.NewSimulatedSource(cfg.seed)
	if cfg.sourceKind == "polling" {
		log.Info("using polling source", "marketBase", cfg.marketBase, "pollInterval", cfg.pollInterval)
		return quote.NewPollingSource(sim, cfg.marketBase, cfg.pollInterval, log)
	}
	return sim
}

func getenv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func getInt(key string, def int, log *slog.Logger) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
		log.Warn("invalid int env, using default", "key", key, "default", def)
	}
	return def
}

func getInt64(key string, def int64, log *slog.Logger) int64 {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil {
			return n
		}
		log.Warn("invalid int64 env, using default", "key", key, "default", def)
	}
	return def
}

func getDuration(key string, def time.Duration, log *slog.Logger) time.Duration {
	if v := os.Getenv(key); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			return d
		}
		log.Warn("invalid duration env, using default", "key", key, "default", def)
	}
	return def
}
