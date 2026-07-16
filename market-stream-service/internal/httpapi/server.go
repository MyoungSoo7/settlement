// Package httpapi wires the HTTP endpoints: health, SSE stream, and an
// optional WebSocket stream, all backed by the Hub.
package httpapi

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"

	"github.com/myoungsoo7/settlement/market-stream-service/internal/hub"
)

// Server holds handler dependencies.
type Server struct {
	hub *hub.Hub
	log *slog.Logger
}

// NewServer builds the HTTP server wiring.
func NewServer(h *hub.Hub, log *slog.Logger) *Server {
	if log == nil {
		log = slog.Default()
	}
	return &Server{hub: h, log: log}
}

// Handler returns the root http.Handler (uses net/http ServeMux with the Go
// 1.22 path-parameter routing).
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.handleHealth)
	mux.HandleFunc("GET /stream/{stockCode}", s.handleSSE)
	mux.HandleFunc("GET /ws/{stockCode}", s.handleWS)
	return mux
}

func (s *Server) handleHealth(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]string{"status": "UP"})
}

// handleSSE streams price ticks as Server-Sent Events. One HTTP request ==
// one Hub subscription; when the client disconnects (request context is
// cancelled) the subscription is torn down and, if it was the last one, the
// code's quote loop stops. No goroutines leak.
func (s *Server) handleSSE(w http.ResponseWriter, r *http.Request) {
	code := strings.TrimSpace(r.PathValue("stockCode"))
	if code == "" {
		http.Error(w, "stockCode required", http.StatusBadRequest)
		return
	}

	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("X-Accel-Buffering", "no") // disable proxy buffering (nginx)

	ticks, unsubscribe := s.hub.Subscribe(code)
	defer unsubscribe()

	// Initial comment so clients see the stream is open immediately.
	_, _ = w.Write([]byte(": connected\n\n"))
	flusher.Flush()

	ctx := r.Context()
	for {
		select {
		case <-ctx.Done():
			s.log.Debug("sse: client disconnected", "code", code)
			return
		case tick, ok := <-ticks:
			if !ok {
				return // Hub closed the channel (shutdown)
			}
			payload, err := json.Marshal(tick)
			if err != nil {
				s.log.Warn("sse: marshal failed", "err", err)
				continue
			}
			// SSE frame: named event + data line, terminated by blank line.
			if _, err := w.Write([]byte("event: tick\ndata: ")); err != nil {
				return
			}
			if _, err := w.Write(payload); err != nil {
				return
			}
			if _, err := w.Write([]byte("\n\n")); err != nil {
				return
			}
			flusher.Flush()
		}
	}
}
