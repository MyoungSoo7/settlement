# ADR 0012 — Outbox 경계에서 끊기지 않는 분산 트레이싱

- 상태: Accepted
- 일자: 2026-04-28

## 컨텍스트

마이크로서비스의 분산 트레이싱은 동기 호출 (HTTP/RPC) 에서는 traceparent 헤더로 자동
전파된다. 그러나 **Lemuel 의 결제 → 정산 흐름은 Outbox 패턴으로 비동기**이다:

```
[HTTP 요청] → 결제 트랜잭션 → outbox INSERT → ✂️경계1✂️ → 폴러 → Kafka publish → ✂️경계2✂️ → 컨슈머 → 정산
```

경계1 (DB tx 커밋 ↔ 폴러 read) 과 경계2 (Kafka send ↔ Kafka receive) 모두에서 trace
context 가 사라진다. 결과적으로 Tempo 에서 같은 요청이 여러 trace 로 끊겨 보임.

## 결정

`outbox_events` 테이블에 `trace_parent` 컬럼 추가. 도메인 트랜잭션 시점의 W3C Trace
Context (`00-{traceId}-{spanId}-{flags}`) 를 영속화하고, 폴러가 발행 시 Kafka 헤더로
복원.

### 흐름

```
Outbox INSERT 시:
  TraceContextCapture.captureCurrentTraceParent() → "00-abc...-def...-01"
  outbox_events.trace_parent 컬럼에 저장

Kafka publish 시 (폴러):
  ProducerRecord.headers().add("traceparent", outbox.traceParent)

Kafka consume 시 (settlement-service):
  spring-kafka 자동 instrumentation 이 traceparent 헤더 읽어
  새 span 을 같은 traceId 로 시작
```

### 결과

Tempo 에서 단일 trace 로 다음을 모두 추적 가능:
- HTTP 결제 요청 span
- 결제 도메인 서비스 span
- Outbox INSERT span
- 폴러 Kafka publish span
- 컨슈머 receive span
- 정산 도메인 서비스 span

## 대안

- **Kafka Consumer 가 이벤트 payload 안에 traceparent 포함하도록** : payload 오염 +
  스키마 변경 필요. 헤더 방식이 더 깨끗
- **새 trace 로 시작** (현재 default): 비동기 경계마다 trace 분리. 운영 시 root cause
  분석이 어려워짐

## 활성화

```yaml
management:
  tracing:
    sampling:
      probability: ${MANAGEMENT_TRACING_SAMPLING_PROBABILITY:1.0}
  otlp:
    tracing:
      endpoint: ${MANAGEMENT_OTLP_TRACING_ENDPOINT:}  # docker-compose 가 Tempo 주입
```

비활성 환경 (`Tracer` 빈 없음) 은 `TraceContextCapture.captureCurrentTraceParent()` 가
null 반환 → `outbox.trace_parent` NULL → 기존 동작과 100% 호환.

## 참조

- [V40 마이그레이션](../../order-service/src/main/resources/db/migration/V40__outbox_traceparent.sql)
- [TraceContextCapture.java](../../shared-common/src/main/java/github/lms/lemuel/common/outbox/application/service/TraceContextCapture.java)
- [docker-compose.yml — Tempo + Grafana](../../docker-compose.yml)
