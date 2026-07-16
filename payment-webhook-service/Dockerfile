# ---- build stage ----
FROM golang:1.22-alpine AS build
WORKDIR /src

# Cache deps first.
COPY go.mod go.sum ./
RUN go mod download

COPY . .
# Static, stripped binary.
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" \
    -o /out/payment-webhook-service ./cmd/server

# ---- runtime stage ----
FROM gcr.io/distroless/static-debian12:nonroot
WORKDIR /

COPY --from=build /out/payment-webhook-service /payment-webhook-service

# distroless:nonroot runs as uid 65532.
USER nonroot:nonroot

ENV PAYMENT_WEBHOOK_PORT=8111
EXPOSE 8111

ENTRYPOINT ["/payment-webhook-service"]
