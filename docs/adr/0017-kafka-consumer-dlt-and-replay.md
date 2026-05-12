# ADR 0017 — Kafka 컨슈머 DLT + 운영자 Replay

- 상태: Accepted
- 일자: 2026-05-08

## 컨텍스트

`payment.captured` 이벤트는 결제 완료 → 정산 자동 생성을 트리거하는 정산 사이클의 시작점이다.
컨슈머 측 멱등성은 이미 3 단으로 방어되어 있다 — `outbox event_id UNIQUE`, `processed_events PK`,
`settlements.payment_id UNIQUE`. 하지만 **재처리 종료 조건이 없다**:

1. Spring Kafka 의 기본 `DefaultErrorHandler` = `FixedBackOff(0, 9)` — 즉시 9 회 재시도 후
   *조용히 skip*. 메시지가 사실상 유실되며 어떤 메트릭도 증가하지 않는다.
2. 페이로드 자체가 깨진 "독성 메시지" 한 개가 컨슈머 그룹 전체의 lag 를 만들 수 있다.
3. 정산 SLA 가 D+1 셀러 입금이므로, 메시지 한 건의 stall 이 사이클 전체를 무너뜨린다.

면접 질문 *"카프카 컨슈머가 한 건 처리 실패하면 어떻게 되죠?"* 에 *"3중 멱등으로 안전합니다"*
만으로는 답이 부족하다 — *"실패가 어디로 가나요? 누가 그걸 보나요? 다시 처리할 수 있나요?"* 가 따라온다.

## 결정

### 1. DefaultErrorHandler + DeadLetterPublishingRecoverer 도입

`KafkaErrorHandlerConfig` 가 autoconfigure 의 `kafkaListenerContainerFactory` 빈을 override 해
다음 동작을 수행:

```
재시도 정책: FixedBackOff(2s, 3 회) — 합계 6 초
독성 메시지 (즉시 DLT, 재시도 0):
  - JsonProcessingException (페이로드 파싱 실패)
  - IllegalArgumentException (도메인 인풋 검증 실패)
  - IllegalStateException  (상태 머신 위반, 이미 종료된 정산 재처리 등)
일시적 예외 (재시도 후 DLT):
  - DB 락 타임아웃, IO 에러, 일시적 네트워크 장애 등
```

### 2. DLT 토픽 명명·보관 정책

- 이름: `<source-topic>.DLT` — Spring Kafka 표준, 도구·복구 자동화 친화적.
- 파티션: 원본과 동일 (3) — key 기반 순서를 replay 시에도 유지.
- 보관: **30 일** (원본 7 일보다 길게) — 운영자가 사후 분석할 시간 확보.

대상 토픽:
- `lemuel.payment.captured.DLT`
- `lemuel.payment.refunded.DLT`

### 3. 운영자 Replay 워크플로

```
GET  /admin/dlq/inspect?topic=...&max=20      — DLT read-only 인스펙션
POST /admin/dlq/replay?topic=...&max=10       — 원본 토픽으로 republish
```

- 권한: ROLE_ADMIN (`SecurityConfig` 에서 `/admin/dlq/**` 강제)
- 감사: 모든 호출이 V34 `audit_logs` 에 기록 — operator, topic, count, timestamp
- 임시 컨슈머 그룹 (`replay-<UUID>`) 사용 → commit 하지 않음 → lag 누적 X
- 원본 헤더 (`event_id`, `traceparent`) 패스스루 → `processed_events` 멱등 자동 작동
- DLT 식별 헤더 (`kafka_dlt-*`) 는 strip 후 republish

### 4. 무한 루프 안전망

`x-replay-count` 헤더로 replay 횟수 추적. **5 회 도달 시 skip** — 운영자가 같은 메시지를
끝없이 replay 하다 시스템을 무너뜨리는 사고 방지. 5 회 후엔 운영자가 페이로드를 직접
수정하거나 폐기 결정 필요.

### 5. 관측성

| 메트릭 | 의미 | 알람 임계 |
|---|---|---|
| `settlement.kafka.dlt.published.total` | DLT 로 라우팅된 누적 메시지 수 | rate 5m > 0.1/s → 운영자 호출 |
| `settlement.kafka.retry.total` | 재시도 시도 누적 (재시도 빈발 = 백엔드 불안 신호) | rate 5m > 1/s → 인프라 점검 |
| `settlement.kafka.dlt.replayed.total` | replay 누적 — 사고 후 정상화 추적 | 정보용 |

## 결과

### 좋아진 점

- **메시지 유실 0**: 모든 실패가 DLT 에 보존되며 메트릭으로 즉시 가시화.
- **파티션 stall 방어**: 독성 메시지가 같은 파티션의 후속 정상 메시지를 막지 않음 (재시도 ≤ 6 초).
- **운영 워크플로 완성**: inspect → 원인 분석 → 수정 → replay 의 사이클이 코드·콘솔 양쪽에 존재.
- **3 단 멱등 → 4 단 멱등**: 기존 멱등 + replay-count cap 으로 운영자 실수까지 방어.

### 트레이드오프

- 재시도 6 초 동안 같은 파티션의 후속 메시지가 대기 — 정산 SLA 가 D+1 이라 6 초는 무시 가능 수준.
- DLT 보관량 증가 — 30 일 × 메시지 평균 1KB × 일 1 만 건 = 약 300MB. 토픽 storage 정책으로 흡수.
- 운영자 권한 분리 부담 — ROLE_ADMIN 만 replay 가능, 감사 로그 강제로 사고 발생 시 추적 가능.

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| `FixedBackOff(0, 9)` 유지 (Spring 기본) | ✗ | 메시지 유실 무성 발생 |
| `ExponentialBackOffWithMaxRetries` | ✗ | 현 클래스패스에 없음. SLA 추론도 fixed 가 더 단순 |
| Replay 를 별도 컨슈머 그룹으로 (장기) | ✗ | 항상 켜둘 필요 없는 코드 — on-demand REST 가 운영 부하 적음 |
| Replay 를 CLI / kafka-console-producer 로만 | ✗ | 권한·감사 통제 불가, 시연 친화도 낮음 |
| BasicAuth 운영자 콘솔 | ✗ | JWT/role 모델과 불일치, 감사 추적 약함 |

## 변경된/추가된 파일

```
shared-common/.../audit/domain/AuditAction.java               +DLQ_INSPECTED/REPLAYED/PURGED
shared-common/.../config/kafka/KafkaConfig.java               +.DLT 토픽 2 개
shared-common/.../config/jwt/SecurityConfig.java              +/admin/dlq/** ROLE_ADMIN
settlement-service/.../in/kafka/KafkaErrorHandlerConfig.java  ★ 신규 — handler + recoverer
settlement-service/.../in/kafka/PaymentEventKafkaConsumer.java JSON 예외 분류 정리
settlement-service/.../in/kafka/DlqReplayService.java         ★ 신규 — inspect/replay
settlement-service/.../in/web/admin/DlqAdminController.java   ★ 신규 — 운영자 콘솔 + 감사
settlement-service/src/main/resources/application.yml         +producer 설정
```

## 후속 과제

- Prometheus 알람 룰 (`rate(settlement_kafka_dlt_published_total[5m]) > 0.1`) — 별도 PR
- DLT 메시지 자동 만료 알림 (30 일 보관 임박) — 별도 PR
- DLT pause/resume 토글 (대량 사고 시 컨슈머 일시 정지) — Phase 6 검토
