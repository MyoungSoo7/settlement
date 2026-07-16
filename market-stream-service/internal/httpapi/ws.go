package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"nhooyr.io/websocket"
)

// handleWS streams price ticks over a WebSocket. Semantics mirror the SSE
// handler: one connection == one Hub subscription, torn down on disconnect.
func (s *Server) handleWS(w http.ResponseWriter, r *http.Request) {
	code := strings.TrimSpace(r.PathValue("stockCode"))
	if code == "" {
		http.Error(w, "stockCode required", http.StatusBadRequest)
		return
	}

	c, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		// InsecureSkipVerify keeps the MVP simple for local/dashboard use.
		// TODO: constrain OriginPatterns for production.
		InsecureSkipVerify: true,
	})
	if err != nil {
		s.log.Warn("ws: accept failed", "err", err)
		return
	}
	defer c.Close(websocket.StatusInternalError, "closing")

	ticks, unsubscribe := s.hub.Subscribe(code)
	defer unsubscribe()

	ctx := r.Context()
	// Detect client-side close by reading (and discarding) in the background;
	// cancels ctx when the peer goes away.
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	go func() {
		for {
			if _, _, err := c.Read(ctx); err != nil {
				cancel()
				return
			}
		}
	}()

	for {
		select {
		case <-ctx.Done():
			c.Close(websocket.StatusNormalClosure, "bye")
			return
		case tick, ok := <-ticks:
			if !ok {
				c.Close(websocket.StatusNormalClosure, "server shutdown")
				return
			}
			payload, err := json.Marshal(tick)
			if err != nil {
				continue
			}
			writeCtx, wc := context.WithTimeout(ctx, 5*time.Second)
			err = c.Write(writeCtx, websocket.MessageText, payload)
			wc()
			if err != nil {
				return
			}
		}
	}
}
