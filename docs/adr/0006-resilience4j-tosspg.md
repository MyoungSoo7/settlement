# ADR 0006 — Toss PG 호출에 Resilience4j (CircuitBreaker + Retry)

**Status:** Accepted
**Date:** 2026-04-23

## Context

Toss Payments API (`/v1/payments/confirm`) 는 외부 의존이다. 장애 시:

- 네트워크 타임아웃·5xx — 일시적. 짧은 재시도로 복구 가능.
- 4xx (잘못된 paymentKey 등) — 비즈니스 오류. 재시도해도 성공하지 않음.
- 장시간 장애 — 모든 결제 요청이 blocking → 쓰레드 풀 고갈 → 전체 서비스 다운.

## Decision

`TossPaymentService.callTossConfirmApi` 에 Resilience4j 의 `@CircuitBreaker` + `@Retry` AOP 적용.

**파라미터:**

| 항목 | 값 | 이유 |
|------|------|------|
| CircuitBreaker slidingWindowSize | 20 | 최근 20건 기준 판정 — 작은 트래픽에서도 빠른 감지 |
| failureRateThreshold | 50% | 절반 이상 실패 시 OPEN |
| waitDurationInOpenState | 30s | OPEN 후 30초간 차단, 이후 HALF-OPEN 시도 |
| Retry maxAttempts | 3 | 과도한 반복 방지 |
| Retry waitDuration | 500ms, multiplier=2 | 지수 백오프 (500ms → 1s → 2s) |
| ignoreExceptions | `HttpClientErrorException` (4xx) | 비즈니스 오류는 재시도·서킷 판정에서 제외 |

**Fallback:** `tossFallback(..., Throwable t)`
- 4xx 는 그대로 전파 (`IllegalStateException`).
- 그 외는 "Toss PG 일시 장애" 메시지로 감싸 상위에 전달.
- 향후 Alertmanager webhook 연계 예정.

**Timeout 보강:**
- Boot 4 에서 `RestTemplateBuilder` 제거 → `SimpleClientHttpRequestFactory` 직접 구성.
- connect=3s, read=5s — 서킷/재시도가 정상 작동하도록 무한 대기 방지.

## Consequences

**Positive**
- Toss 장시간 장애 시 서비스 전체 마비 방지.
- 일시 네트워크 오류는 투명하게 재시도.
- 4xx 비즈니스 오류는 즉시 사용자에게 전달 (재시도 지연 없음).

**Negative / Trade-offs**
- Resilience4j AOP 의존성 추가.
- Fallback 메서드 시그니처가 원본 + Throwable 로 강제 → 테스트 보일러플레이트.

## Related

- 메트릭: `resilience4j_circuitbreaker_state{name="tossPg"}`, `TossPgCircuitOpen` 알림 룰 (alert-rules.yml)
- 구성: `application.yml` `resilience4j.circuitbreaker.instances.tossPg.*`, `resilience4j.retry.instances.tossPg.*`
