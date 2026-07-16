// Package httpserver builds the HTTP router for the payment-webhook-service.
package httpserver

import (
	"encoding/json"
	"net/http"

	"github.com/myoungsoo7/settlement/payment-webhook-service/internal/webhook"
)

// NewRouter returns the service's HTTP handler with /healthz and the Toss
// webhook endpoint wired in.
func NewRouter(webhookHandler *webhook.Handler) http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(map[string]string{"status": "UP"})
	})

	mux.Handle("POST /webhooks/toss", webhookHandler)

	return mux
}
