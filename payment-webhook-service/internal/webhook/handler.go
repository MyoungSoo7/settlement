package webhook

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"time"

	"github.com/myoungsoo7/settlement/payment-webhook-service/internal/idempotency"
	"github.com/myoungsoo7/settlement/payment-webhook-service/internal/publisher"
)

// maxBodyBytes caps the webhook body we will read into memory.
const maxBodyBytes = 1 << 20 // 1 MiB

// TossWebhook is the incoming Toss-style payment webhook payload.
type TossWebhook struct {
	EventType string          `json:"eventType"`
	Data      TossPaymentData `json:"data"`
}

// TossPaymentData is the nested payment object of a Toss webhook.
type TossPaymentData struct {
	PaymentKey  string `json:"paymentKey"`
	OrderID     string `json:"orderId"`
	Status      string `json:"status"`
	TotalAmount int64  `json:"totalAmount"`
}

// response is the JSON body returned to the webhook caller.
type response struct {
	Received  bool `json:"received"`
	Duplicate bool `json:"duplicate"`
}

// Handler ingests Toss payment webhooks: verify HMAC, dedupe, then publish.
type Handler struct {
	secret    string
	store     idempotency.IdempotencyStore
	publisher publisher.EventPublisher
	logger    *slog.Logger
	now       func() time.Time // injectable clock
}

// NewHandler wires a webhook Handler. A nil logger falls back to slog.Default.
func NewHandler(secret string, store idempotency.IdempotencyStore, pub publisher.EventPublisher, logger *slog.Logger) *Handler {
	if logger == nil {
		logger = slog.Default()
	}
	return &Handler{
		secret:    secret,
		store:     store,
		publisher: pub,
		logger:    logger,
		now:       time.Now,
	}
}

// ServeHTTP implements the POST /webhooks/toss endpoint.
func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	ctx := r.Context()

	// (0) Capture the RAW body before JSON decode — HMAC is over exact bytes.
	body, err := io.ReadAll(io.LimitReader(r.Body, maxBodyBytes))
	if err != nil {
		h.logger.ErrorContext(ctx, "failed to read body", "err", err)
		http.Error(w, "cannot read body", http.StatusBadRequest)
		return
	}

	// (1) Verify HMAC signature — constant-time. Reject on mismatch.
	sig := r.Header.Get(SignatureHeader)
	if !VerifySignature(h.secret, body, sig) {
		h.logger.WarnContext(ctx, "signature verification failed")
		http.Error(w, "invalid signature", http.StatusUnauthorized)
		return
	}

	// Decode after signature passes.
	var hook TossWebhook
	if err := json.Unmarshal(body, &hook); err != nil {
		h.logger.ErrorContext(ctx, "invalid json", "err", err)
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}
	if hook.Data.PaymentKey == "" {
		http.Error(w, "missing paymentKey", http.StatusBadRequest)
		return
	}

	// (2) Idempotency: dedupe by paymentKey + eventType.
	dedupeKey := hook.EventType + ":" + hook.Data.PaymentKey
	isNew, err := h.store.MarkIfNew(ctx, dedupeKey)
	if err != nil {
		h.logger.ErrorContext(ctx, "idempotency store error", "err", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if !isNew {
		// Duplicate delivery: ack 200 but do NOT re-publish.
		h.logger.InfoContext(ctx, "duplicate webhook ignored", "paymentKey", hook.Data.PaymentKey, "eventType", hook.EventType)
		writeJSON(w, http.StatusOK, response{Received: true, Duplicate: true})
		return
	}

	// (3) Publish normalized domain event.
	event := publisher.PaymentConfirmedEvent{
		EventType:   hook.EventType,
		PaymentKey:  hook.Data.PaymentKey,
		OrderID:     hook.Data.OrderID,
		Status:      hook.Data.Status,
		TotalAmount: hook.Data.TotalAmount,
		OccurredAt:  h.now().UTC().Format(time.RFC3339),
	}
	if err := h.publisher.Publish(ctx, event); err != nil {
		h.logger.ErrorContext(ctx, "publish failed", "err", err, "paymentKey", event.PaymentKey)
		http.Error(w, "publish failed", http.StatusInternalServerError)
		return
	}

	h.logger.InfoContext(ctx, "webhook accepted", "paymentKey", event.PaymentKey, "eventType", event.EventType)
	writeJSON(w, http.StatusOK, response{Received: true, Duplicate: false})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
