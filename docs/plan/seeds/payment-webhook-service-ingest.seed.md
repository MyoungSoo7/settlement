# Seed — payment-webhook-service 결제 웹훅 수신 as-is 사양

> **상태: CONFIRMED** (2026-08-13) · 정본 데이터: [`payment-webhook-service-ingest.seed.yaml`](payment-webhook-service-ingest.seed.yaml)
> Ouroboros 방법론(Interview → Seed)으로 결정화.

## Goal (한 줄)

**payment-webhook-service(Go 폴리글랏 8111 — 원문 바이트 HMAC 상수시간 검증·`eventType:paymentKey` TTL 멱등·
자사 계약 정규화 후 `lemuel.payment.confirmed` 발행·브로커 없으면 Log 폴백)의 현행 동작을 실행 가능한
게이트에 매핑된 불변 사양으로 결정화한다.**

## 범위

| 포함                                      | 제외                                |
| ----------------------------------------- | ----------------------------------- |
| 서명 검증 (원문 바이트·상수시간·fail-closed) | 결제 승인/취소 판단(order)         |
| 멱등 (키·TTL·지연 축출)                   | 정산 반영(settlement) · PG 대사     |
| 정규화 발행 (스키마·key 파티셔닝·폴백)    |                                     |
| HTTP 표면 (상한·상태코드·shutdown)        |                                     |

## 핵심 불변식 (as-is, 파일:라인 근거)

1. **서명은 바이트에 대한 것** — 파싱 전에 원문으로 HMAC 검증, 통과 후에만 JSON 디코드 (`handler.go:68-90`).
2. **상수시간 비교** — `hmac.Equal`, 빈 시크릿·빈 헤더는 즉시 false, base64 디코드 실패는 "불일치" (`signature.go:27-43`).
3. **중복은 성공이다** — 200 + `duplicate:true`, 재발행 없음. 200 을 안 주면 PG 재전송이 멈추지 않는다.
4. **check-and-set 원자성** — 동시 중복 배달 레이스를 단일 mutex 로 차단 (`store.go:13-56`).
5. **하류는 우리 계약을 본다** — `PaymentConfirmedEvent` 는 Toss 원형과 의도적으로 분리.
6. **결제별 순서** — `kafka.Hash{}` + key=paymentKey, `RequiredAcks=RequireAll`.
7. **브로커 없이 돈다** — `KAFKA_BROKERS` 미설정 시 `LogPublisher`.
8. **fail-closed** — 시크릿이 없으면 뜨되 전 요청 401.

## 이벤트 계약

| 방향 | 토픽 | 비고 |
|------|------|------|
| 발행 | `lemuel.payment.confirmed` (key=paymentKey) | 소비자 = notification-service. **계약 스키마 미등록**(ADR 0024 미참여) |
| 소비 | 없음 | |

## 수용 기준 (게이트 매핑)

| AC   | 기준                                   | 게이트                        |
| ---- | -------------------------------------- | ----------------------------- |
| AC-1 | HMAC·상수시간·빈 값 거부 일치          | `signature_test.go`           |
| AC-2 | 상태코드 계약(400/401/200 dup/500) 일치 | `handler_test.go`            |
| AC-3 | 멱등 check-and-set·TTL 재마킹 일치     | `store_test.go`               |
| AC-4 | Go 빌드·vet·race GREEN                 | `polyglot-ci.yml` (Go 매트릭스) |

## Known Issues (발견만 기록)

- **KI-1 ★high**: **발행 실패 이벤트 영구 유실** — 멱등 선점이 발행보다 먼저이고 롤백이 없어, 재전송이 `duplicate:true` 200 으로 흡수된다. 유실 창 24시간.
- **KI-2 ★**: 서명 스킴이 Toss 실규격이 아니다(주석 명문) — 진짜 웹훅은 한 건도 통과 못 한다.
- **KI-3 ★high**: 외부 도달 경로 없음(compose·게이트웨이 부재) — 현재 어떤 웹훅도 받지 않는다.
- **KI-4**: `lemuel.payment.confirmed` 계약 스키마 미등록 — 프로듀서·컨슈머 모두 shared-common 미의존이라 계약 테스트 불가.
- **KI-5**: 필드명이 Java 규약과 다름(`paymentKey`/`totalAmount`) — 컨슈머 폴백에 의존.
- **KI-6**: `event_id` 헤더 미발행 → 하류 dedupe 가 paymentKey 로 떨어져 같은 결제의 두 번째 이벤트가 스킵될 수 있다.
- **KI-7**: `AllowAutoTopicCreation: true` — 오타가 조용한 새 토픽이 된다.
- **KI-8**: 멱등 휘발성(재시작·스케일아웃 시 중복 발행). 유일 컨슈머도 인메모리 dedupe 라 같은 성질.
- **KI-9**: `/metrics`·카운터 없음 — KI-1 발생을 감지할 수단이 없다.
