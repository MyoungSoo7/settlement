---
name: settlement-security-patterns
description: Recurring security patterns, architectural decisions, and known vulnerabilities found in the settlement repo during the 2026-06-18 audit
metadata:
  type: project
---

## Safe Patterns (Confirmed)

- **All @Query annotations use named parameters** (`:paymentId`, `:parentId`, etc.) — no string concat SQL injection. JPQL throughout, no raw native queries in main source. Safe.
- **JWT signing uses `Jwts.parser().verifyWith(secretKey)`** — real HMAC-SHA256 signature verification in `JwtUtil.parseToken()`. Secret enforced >= 32 bytes at startup.
- **BCryptPasswordEncoder(12)** — cost=12 confirmed in `SecurityConfig.java:37`.
- **CSRF disabled with documented justification** — JWT stateless, comment on line 101 of SecurityConfig.
- **CORS** — env-var controlled (`cors.origins` / `cors.allowed-origins`), localhost fallback for dev only. Production uses helm-deploy chart injection.
- **Pessimistic locks** — `@Lock(PESSIMISTIC_WRITE)` present on settlement repo (`SpringDataSettlementJpaRepository:25,41,56`) and payment (`PaymentJpaRepository:32`).
- **Idempotency** — Refund idempotency enforced via `idempotency_key` UNIQUE DB column + `findByPaymentIdAndIdempotencyKey`. `MissingIdempotencyKeyException` thrown for partial refunds without key.
- **GlobalExceptionHandler** — 500 fallback returns generic message "일시적인 오류가 발생했습니다", does NOT leak stack traces to client.
- **Toss secret-key** — loaded from `${TOSS_SECRET_KEY}` env var, NOT hardcoded. Confirmed in `application.yml:286`.
- **FirmBanking logs use maskedAccountNumber()** — `account.maskedAccountNumber()` called before logging in `MockFirmBankingAdapter:33,39`.
- **Mass assignment** — `UpdateProfileRequest` only exposes `name` and `phoneNumber` fields. No `role`/`id` settable from user-facing DTOs.

## Known Vulnerabilities

### CRITICAL — BOLA on Payment Endpoints
- `PATCH /payments/{id}/authorize` and `PATCH /payments/{id}/capture` and `PATCH /payments/{id}/refund` in `PaymentController.java` have NO owner check. Any authenticated user can authorize/capture/refund ANY payment by ID.
- `GET /payments/{id}` — also no owner check.
- `GET /api/payments/{paymentId}/refunds` in `RefundHistoryController.java` — exposes full refund history (including idempotency keys) with no owner check.
- SecurityConfig only covers role-based rules at the path pattern level; no method-level `@PreAuthorize("#id == principal.userId")` present.

### HIGH — paymentKey Logged in Error Path
- `TossPaymentService.java:176` — `tossFallback()` logs `paymentKey={}` to ERROR. paymentKey is a Toss payment token equivalent to a payment credential. PCI-DSS requires this to be masked or excluded from logs.

### HIGH — No Toss Webhook Signature Verification
- No inbound Toss webhook receiver controller exists. `ChargebackAdminController.java:37` comment says "Phase 3 — HMAC 서명 검증 등 별도 보안 설계 필요". Chargeback webhook is not yet protected.

### MEDIUM — Registration Endpoint Not Rate Limited
- `POST /users` (signup) and `POST /users/password-reset/**` are NOT covered by any `RateLimitPolicy`. Only `/auth/login` (5/min IP), `/payments` (10/min actor), `/admin` (30/min actor) are covered. Brute-force account creation / password-reset token enumeration possible.

### MEDIUM — X-Forwarded-For Header Spoofable for IP-Based Rate Limiting
- `RateLimitFilter.extractIp()` trusts the first value of `X-Forwarded-For` without any gateway-level stripping validation. If the gateway does not strip this header before forwarding, an attacker can spoof their IP to bypass the login rate limit.

## Sensitive Field Names to Watch
- `paymentKey` — Toss payment token, treat like card credential
- `secretKey` in TossPaymentService — bound to `${TOSS_SECRET_KEY}`
- `idempotencyKey` in Refund — appears in `RefundItem` API response (over-exposure)
- `passwordHash` — never exposed in UserResponse (confirmed safe)

**Why:** Built up from first full security audit 2026-06-18.
**How to apply:** Use as checklist when reviewing PRs touching payment, refund, settlement, or user controllers.
