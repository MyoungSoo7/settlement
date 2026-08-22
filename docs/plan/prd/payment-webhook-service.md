# PRD — 결제 웹훅 수신 (payment-webhook-service, 폴리글랏 Go)

> **문서 성격**: 구현된 코드에서 **거꾸로 역산한(reverse-engineered) 제품 요구사항 문서**다.
> 자매 문서 [`order-service.md`](order-service.md)·[`market-stream-service.md`](market-stream-service.md) 와 같은 규약을 쓴다 —
> 새 기능을 제안하지 않고, 이미 동작 중인 시스템이 *무엇을, 왜, 어떤 규칙으로* 하는지를 제품 관점으로 재진술한다.
>
> | 항목      | 값                                                                                       |
> | --------- | ------------------------------------------------------------------------------------------ |
> | 대상 범위 | `payment-webhook-service`(8111, Go 1.22, **DB 없음**) — PG 결제 웹훅 수신 → 정규화 이벤트 발행 |
> | 역산 기준 | 2026-08-13 `develop` 브랜치                                                              |
> | 근거      | Go 프로덕션 7파일, 테스트 9파일, `lemuel.payment.confirmed` 발행 계약                    |
> | 범위 밖   | 결제 승인·취소(→ `order-service`) · 정산 반영(→ `settlement-service`) · PG 대사          |
> | 관련 문서 | [`../polyglot-services.md`](../polyglot-services.md) · ADR 0024(이벤트 계약) · [`../seeds/payment-webhook-service-ingest.seed.yaml`](../seeds/payment-webhook-service-ingest.seed.yaml) |

---

## 1. 배경과 문제

PG 웹훅은 **믿을 수 없는 인터넷 요청이 돈에 관한 사실을 들고 오는** 창구다. 세 가지가 동시에 필요하다.

| 문제              | 구체적 손상                                                                     |
| ----------------- | ------------------------------------------------------------------------------- |
| **위조**          | 서명 검증이 없으면 누구나 "결제됐다"고 주장할 수 있다                          |
| **재전송**        | PG 는 200 을 받을 때까지 재시도한다 — 그대로 처리하면 이벤트가 중복 발행된다   |
| **벤더 결합**     | Toss 페이로드 모양을 하류가 그대로 소비하면 PG 교체가 전 서비스 변경이 된다     |
| **응답 지연**     | JVM 이 콜드 스타트/GC 로 늦으면 PG 가 타임아웃으로 재전송을 늘린다             |

payment-webhook-service 는 **서명 검증 → 멱등 선점 → 자사 계약으로 정규화 발행** 3단만 하는 얇은 Go 엣지다.
핵심 설계 판단은 하나다 — **하류는 Toss 계약이 아니라 우리 계약에 의존한다.**

## 2. 목표 / 비목표

### 2.1 목표

| #  | 목표                                     | 성공 기준                                               |
| -- | ---------------------------------------- | ------------------------------------------------------- |
| G1 | 위조 웹훅을 받지 않는다                  | HMAC-SHA256(원문 바이트) 상수시간 비교, 불일치 401      |
| G2 | 재전송이 이벤트를 늘리지 않는다          | `eventType:paymentKey` 선점 — 중복은 200 + 미발행       |
| G3 | 하류가 PG 모양에 묶이지 않는다           | `PaymentConfirmedEvent` 자사 스키마로 정규화            |
| G4 | 결제별 순서가 보존된다                   | Kafka key = `paymentKey`, Hash 밸런서                   |
| G5 | 브로커 없이도 end-to-end 로 돈다         | `KAFKA_BROKERS` 미설정 시 `LogPublisher` 폴백           |
| G6 | 키 없으면 아무 것도 받지 않는다          | `TOSS_WEBHOOK_SECRET` 빈 값 → 전 요청 401(fail-closed)  |

### 2.2 비목표 (의도적으로 하지 않는 것)

| #  | 비목표                | 이유                                                        |
| -- | --------------------- | ----------------------------------------------------------- |
| N1 | 웹훅 원문 영속화      | DB 없음 — 무영속 엣지                                       |
| N2 | 결제 상태 판단        | 승인/취소 판단은 `order-service` 도메인                     |
| N3 | 재시도·DLQ            | 발행 실패 시 비200 을 돌려 PG 의 재전송에 맡긴다            |
| N4 | 여러 PG 지원          | 현재 Toss 형태 1종                                          |
| N5 | Outbox 패턴           | DB 가 없어 트랜잭션 아웃박스가 성립하지 않는다              |

## 3. 사용자

| 사용자          | 무엇을 위해 쓰는가                                    |
| --------------- | ----------------------------------------------------- |
| **PG(Toss)**    | 결제 상태 변화를 통지                                 |
| **하류 컨슈머** | `lemuel.payment.confirmed` 를 구독(현재 notification) |
| **운영자**      | `/healthz` 로 생존 확인                               |

## 4. 제품 범위 — 기능 맵

| 영역   | 기능                                                     |
| ------ | -------------------------------------------------------- |
| 수신   | `POST /webhooks/toss` — 본문 1MiB 상한                    |
| 검증   | 원문 바이트 HMAC-SHA256 base64, 상수시간 비교            |
| 멱등   | 메모리 TTL 24시간, 키 = `eventType:paymentKey`            |
| 발행   | `lemuel.payment.confirmed`, acks=all, key=paymentKey     |
| 운영   | `/healthz`, graceful shutdown 10초, distroless non-root  |

## 5. 핵심 유스케이스

### UC-1. 결제 통지를 안전하게 받아 이벤트로 바꾼다

1. PG 가 `POST /webhooks/toss` 로 서명 헤더(`Toss-Signature`)와 함께 보낸다.
2. **JSON 파싱 전에** 원문 바이트를 읽어 HMAC 을 계산한다 — 파싱·재직렬화하면 바이트가 달라져 서명이 깨진다.
3. 불일치면 401. 통과하면 파싱하고 `paymentKey` 필수 검사(없으면 400).
4. `eventType:paymentKey` 를 멱등 스토어에 선점한다.
5. 자사 스키마로 정규화해 `lemuel.payment.confirmed` 에 발행한다(key = paymentKey → 결제별 파티션 고정 = 순서 보존).
6. `{"received":true,"duplicate":false}` 200.

### UC-2. PG 재전송이 이벤트를 늘리지 않는다

1. 같은 `eventType:paymentKey` 가 24시간 안에 다시 오면 선점에 실패한다.
2. **200 + `duplicate:true`** 로 응답하고 발행하지 않는다 — PG 에겐 성공이므로 재시도가 멈춘다.

### UC-3. 브로커 없이 데모한다

1. `KAFKA_BROKERS` 가 비어 있으면 `LogPublisher` 가 선택된다.
2. 이벤트가 구조적 로그로 출력되며 전 경로(서명·멱등·정규화)가 그대로 검증된다.

## 6. 기능 요구사항

| FR   | 요구사항                                                    | 강제 지점                            |
| ---- | ----------------------------------------------------------- | ------------------------------------ |
| FR-1 | 서명 검증은 JSON 파싱보다 먼저 원문 바이트로 한다           | `handler.go` (0)→(1) 순서            |
| FR-2 | 비교는 상수시간이다                                         | `hmac.Equal`                         |
| FR-3 | 시크릿 또는 서명 헤더가 비면 거부한다                       | `VerifySignature` 가드               |
| FR-4 | 본문은 1MiB 로 제한한다                                     | `io.LimitReader`                     |
| FR-5 | `paymentKey` 가 없으면 400                                  | 핸들러 필수 검사                     |
| FR-6 | 중복은 200 + `duplicate:true`, 미발행                       | 핸들러 (2)                           |
| FR-7 | 발행 실패는 500 — PG 재전송에 맡긴다                        | 핸들러 (3)                           |
| FR-8 | Kafka 는 acks=all, key 해시 파티셔닝                        | `KafkaPublisher`                     |
| FR-9 | 종료 시 10초 안에 연결을 비운다                             | `srv.Shutdown`                       |

## 7. 도메인 규칙 (BR)

| BR   | 규칙                                                                                          | 근거                          |
| ---- | --------------------------------------------------------------------------------------------- | ----------------------------- |
| BR-1 | **서명은 바이트에 대한 것이다** — 파싱된 객체가 아니라 수신 원문에 대해 검증한다               | 핸들러 (0) 주석               |
| BR-2 | **중복은 성공이다** — PG 에게 200 을 주지 않으면 재전송이 영원히 계속된다                      | 핸들러 (2)                    |
| BR-3 | **하류는 우리 계약을 본다** — Toss 모양을 그대로 흘리지 않는다                                 | `publisher.go` 타입 주석      |
| BR-4 | **fail-closed** — 시크릿이 없으면 서비스는 뜨되 모든 요청을 거절한다                           | `main.go` warn + 검증 가드    |
| BR-5 | **결제별 순서는 key 로 보존한다** — 같은 paymentKey 는 같은 파티션으로 간다                    | `kafka.Hash{}` 밸런서         |

## 8. 데이터 모델

**DB 없음.**

| 구조                     | 역할                          | 상한             |
| ------------------------ | ----------------------------- | ---------------- |
| `MemoryStore.items`      | `eventType:paymentKey` → 만료 | TTL 24시간(지연 축출) |
| `PaymentConfirmedEvent`  | 발행 페이로드                 | —                |

## 9. 인터페이스

### 9.1 HTTP

| 메서드 | 경로              | 인증           | 응답                                     |
| ------ | ----------------- | -------------- | ---------------------------------------- |
| POST   | `/webhooks/toss`  | HMAC 서명 헤더 | 200 `{received,duplicate}` / 400 / 401 / 500 |
| GET    | `/healthz`        | 없음           | `{"status":"UP"}`                        |

### 9.2 이벤트 (발행)

**토픽**: `lemuel.payment.confirmed` · **key**: `paymentKey`

```json
{ "eventType": "...", "paymentKey": "...", "orderId": "...",
  "status": "...", "totalAmount": 0, "occurredAt": "RFC3339(UTC)" }
```

소비: `notification-service`(Kotlin). Java 서비스 중 이 토픽을 구독하는 컨슈머는 없다.

## 10. 비기능 요구

| NFR   | 요구                    | 현재 상태                                              |
| ----- | ----------------------- | ------------------------------------------------------ |
| NFR-1 | 낮은 응답 지연          | Go 정적 바이너리, 콜드스타트 없음                      |
| NFR-2 | 헤더 슬로로리스 방어    | `ReadHeaderTimeout 5s`                                 |
| NFR-3 | 최소 이미지·비루트      | distroless static nonroot(uid 65532), `CGO_ENABLED=0`  |
| NFR-4 | 타이밍 공격 방어        | `hmac.Equal`                                           |
| NFR-5 | CI                      | `polyglot-ci.yml` Go 매트릭스(build + vet + `test -race`) |

## 11. 배치

**없음.** 멱등 스토어 축출도 별도 스케줄 없이 조회 시 지연 축출된다.

## 12. 역산에서 드러난 격차

### G-1. 발행 실패한 이벤트는 영원히 발행되지 않는다 ★

멱등 선점(2)이 **발행(3)보다 먼저** 일어나고, 발행이 실패해도 선점을 되돌리지 않는다. 따라서:

1. 1차 요청 → 선점 성공 → Kafka 발행 실패 → 500 응답
2. PG 재전송 → 선점 **실패**(24시간 TTL 안) → `duplicate:true` 200 응답 → **발행 없음**

PG 는 성공으로 알고 재시도를 멈추고, 이벤트는 어디에도 남지 않는다. 500 을 돌려 재전송에 맡긴다는
설계 의도(N3)가 멱등 순서 때문에 무력화된다. 유실 창은 TTL 24시간이다.

### G-2. 서명 스킴이 Toss 실제 규격이 아니다 ★

`signature.go` 주석이 명시한다 — "generic HMAC-over-raw-body scheme. Toss' production webhook signature
spec differs (see README TODO) and must be substituted before real use." 즉 **현재 구현으로는 진짜 Toss
웹훅을 한 건도 통과시키지 못한다.** 검증 배관·헤더 캡처·상수시간 비교는 재사용 가능하지만 계산식이 다르다.

### G-3. 이 서비스로 들어오는 경로가 없다 ★

`docker-compose.yml` 에 정의가 없고 게이트웨이 라우트도 없다. 외부 PG 가 도달할 수 있는 공개 엔드포인트가
존재하지 않으므로, 현재 이 서비스는 **어떤 웹훅도 받지 않는다.** 실제 결제 웹훅은 order-service 의
`/admin/pg/**` 계열 경로가 처리한다.

### G-4. `lemuel.payment.confirmed` 가 계약 스키마 없이 흐른다

ADR 0024 는 cross-service 토픽의 JSON Schema + 정본 샘플을 `shared-common/src/testFixtures/resources/contracts/events/`
에 두고 양방향 계약 테스트로 드리프트를 막는다. 실측 결과 그 디렉터리에 **`lemuel.payment.confirmed` 스키마가
없다**(`payment.captured`·`payment.refunded` 는 있다). 프로듀서(Go)도 컨슈머(Kotlin)도 shared-common 을
쓰지 않으므로 계약 테스트에 참여할 수 없고, 드리프트는 런타임에만 드러난다.

### G-5. 페이로드 필드명이 Java 규약과 다르다

Java Outbox 이벤트는 `paymentId`·`amount` 를, 이 서비스는 `paymentKey`·`totalAmount` 를 쓴다.
`notification-service` 의 템플릿이 이 차이를 **폴백 체인으로 흡수하고 있다**(`fields["paymentId"] ?:
fields["paymentKey"]`, `amount ?: … ?: totalAmount`). 즉 두 계약의 불일치가 컨슈머 코드에 비용으로 전가돼 있다.

### G-6. `event_id` 헤더가 없어 하류 멱등이 약해진다

Java Outbox 발행기는 이벤트 UUID 를 `event_id` **헤더**에 싣지만 이 서비스는 싣지 않고, 페이로드에도
`eventId`/`id` 필드가 없다. `notification-service` 의 eventId 결정은 `헤더 → payload → kafka key` 순이라
Go 이벤트는 **kafka key(=paymentKey)** 로 떨어진다. 같은 `paymentKey` 로 서로 다른 `eventType` 두 건을
보내면 두 번째는 하류에서 중복으로 스킵된다 — 이 위험을 notification 코드 주석이 정확히 지적하고 있다.

### G-7. `AllowAutoTopicCreation: true`

토픽명이 틀려도 브로커가 조용히 새 토픽을 만든다. 파티션 수·복제 계수는 브로커 기본값이 되어 운영 설계와
어긋날 수 있고, 오타는 "발행은 성공했는데 아무도 못 받는" 형태로 나타난다.

### G-8. 멱등이 휘발성이다

`MemoryStore` 는 프로세스 메모리다. 재시작·스케일아웃 시 dedupe 가 비어 **같은 웹훅이 다시 발행**된다.
`polyglot-services.md` 가 알려진 한계로 명시하며, 하류 Java 컨슈머의 `processed_events` 멱등이 회계 영향을
막는다 — 다만 이 토픽의 유일한 컨슈머인 notification 은 `processed_events` 가 아니라 자체 인메모리
dedupe 를 쓴다(같은 휘발성).

### G-9. 관측 수단이 로그뿐이다

`/metrics` 도, 카운터도 없다. 서명 실패율·중복률·발행 실패 건수를 지표로 볼 수 없어 G-1 이 실제로
발생해도 알림을 걸 방법이 없다. `MemoryStore.Len()` 은 "tests and diagnostics" 용으로만 존재한다.

## 13. 추적 항목

| #   | 항목                                                  | 상태               |
| --- | ----------------------------------------------------- | ------------------ |
| T-1 | 발행 성공 후 멱등 확정(또는 실패 시 선점 해제)        | 미구현 (G-1)       |
| T-2 | Toss 실제 서명 규격 적용                              | TODO 주석 (G-2)    |
| T-3 | 외부 도달 경로(compose·게이트웨이·PG 콘솔 등록)       | 없음 (G-3)         |
| T-4 | `lemuel.payment.confirmed` 계약 스키마·샘플 등록      | 없음 (G-4)         |
| T-5 | 필드명 규약 통일 또는 계약 문서화                     | 폴백 의존 (G-5)    |
| T-6 | `event_id` 헤더 발행                                  | 없음 (G-6)         |
| T-7 | `AllowAutoTopicCreation` 비활성                       | 활성 (G-7)         |
| T-8 | 내구 멱등 스토어                                      | 인메모리 (G-8)     |
| T-9 | `/metrics` + 서명실패·중복·발행실패 카운터            | 없음 (G-9)         |
