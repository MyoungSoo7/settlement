# syntax=docker/dockerfile:1

# ---- build stage ----
FROM golang:1.22 AS build
WORKDIR /src

# Cache dependencies first.
COPY go.mod go.sum ./
RUN go mod download

# Build statically so it runs on distroless (no libc).
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" \
    -o /out/market-stream-service ./cmd/server

# ---- runtime stage ----
FROM gcr.io/distroless/static-debian12:nonroot
WORKDIR /app
COPY --from=build /out/market-stream-service /app/market-stream-service

# Non-root (provided by the :nonroot distroless variant, uid 65532).
USER nonroot:nonroot

EXPOSE 8110
ENV MARKET_STREAM_PORT=8110

ENTRYPOINT ["/app/market-stream-service"]
