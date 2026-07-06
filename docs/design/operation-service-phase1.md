# operation-service Phase 1 상세 설계 — 골격 + Alertmanager 인시던트화

> 상태: **구현 완료** (2026-07-06). 설계 대비 변경분은 문서 말미 "구현 노트" 참조.
> 선행 문서: 없음 (operation-service 도입 제안 — 전체 로드맵 Phase 1~4 중 1단계)
> 관련 ADR: 0001(헥사고날), 0003(Outbox), 0017(DLT), 0020(DB 분리), 0021(shared-common)

## 1. 범위

Phase 1 은 **"기존 Prometheus 알람 자산을 인시던트로 흡수하고, 운영자가 라이프사이클을 관리할 수 있는 최소 동작 서비스"** 까지다.

| 포함 | 제외 (후속 Phase) |
|------|------|
| 모듈 골격 (`operation-service`, port 8092) | Kafka 실패 이벤트 신설·구독 (Phase 2) |
| 자체 DB `lemuel_operation` + Flyway V1 | Prometheus 폴링 / `ops_metric_bucket` (Phase 2) |
| Alertmanager webhook → Incident 적재 (자동 open/refire/resolve) | 베이스라인 이상 탐지 (Phase 3) |
| Incident 관리 API (조회/ack/resolve/false-positive/코멘트/요약) | AI 인사이트·일일 브리핑 (Phase 4) |
| compose · alertmanager.yml · prometheus.yml · gateway 변경 | 프론트 운영 대시보드 (Phase 4) |

## 2. 모듈 골격

### 2.1 배치

```
operation-service/                    # 🖥️ 운영 관제 (로컬 port 8092, 컨테이너 내부 8080)
└── src/main/java/github/lms/lemuel/operation/
    ├── incident/                     # Phase 1 유일 BC
    │   ├── domain/
    │   │   ├── Incident.java                 # 애그리게잇 루트 (POJO, 상태머신)
    │   │   ├── IncidentStatus.java           # OPEN/ACKNOWLEDGED/RESOLVED/FALSE_POSITIVE
    │   │   ├── IncidentSeverity.java         # CRITICAL/WARNING/INFO
    │   │   ├── IncidentSource.java           # ALERTMANAGER/ANOMALY/MANUAL (Phase 3 대비 선점)
    │   │   ├── SignalCategory.java           # 9종 + INFRA_ETC + UNKNOWN
    │   │   └── IncidentTimelineEntry.java    # 타임라인 항목 (VO)
    │   ├── application/
    │   │   ├── port/in/
    │   │   │   ├── IngestAlertUseCase.java           # webhook → 인시던트 반영
    │   │   │   ├── TransitionIncidentUseCase.java    # ack/resolve/false-positive
    │   │   │   ├── CommentIncidentUseCase.java
    │   │   │   └── IncidentQuery.java                # 목록/단건/요약 조회
    │   │   ├── port/out/
    │   │   │   ├── LoadIncidentPort.java
    │   │   │   ├── SaveIncidentPort.java
    │   │   │   └── RecordTimelinePort.java
    │   │   └── service/
    │   │       ├── IngestAlertService.java
    │   │       ├── IncidentCommandService.java
    │   │       └── IncidentQueryService.java
    │   └── adapter/
    │       ├── in/web/
    │       │   ├── AlertmanagerWebhookController.java
    │       │   ├── IncidentController.java
    │       │   └── dto/  (AlertmanagerPayload, IncidentResponse, ...)
    │       └── out/persistence/
    │           ├── IncidentJpaEntity.java / IncidentTimelineJpaEntity.java
    │           ├── SpringDataIncidentRepository.java
    │           └── IncidentPersistenceAdapter.java
    ├── config/
    │   ├── OperationSecurityConfig.java      # JWT(ADMIN) + webhook 전용 필터 조립
    │   ├── OpsWebhookAuthFilter.java         # Authorization: Bearer <INTERNAL_API_KEY>
    │   └── CategoryMappingProperties.java    # component 라벨 → SignalCategory
    └── OperationServiceApplication.java      # 자체 @SpringBootApplication (loan 패턴)
```

- **의존**: `github.lms.lemuel:shared-common:1.0.0` 만. order/settlement/loan import 0 (기존 경계 원칙).
- loan-service 처럼 **처음부터 독립 부팅** — 자체 DB 소유이므로 library-mode 아님, bootJar 활성.

### 2.2 settings.gradle.kts

```kotlin
include(
    "order-service",
    "settlement-service",
    "loan-service",
    "financial-statements-service",
    "gateway-service",
    "operation-service",          // ★ 추가
)
```

### 2.3 build.gradle.kts (Phase 1 의존성)

loan-service 복사 기반에서 다음만 다름:

- **제외**: Kafka(starter-kafka, spring-kafka) — Phase 2 에서 추가. QueryDSL — Phase 1 조회는 Spring Data 파생 쿼리 + JPQL 로 충분, 필요 시 Phase 2 에 도입.
- **포함**: web, data-jpa, security, validation, actuator, cache, flyway(+postgresql), springdoc, caffeine, bucket4j, micrometer-prometheus, postgresql 드라이버, lombok, mapstruct, testcontainers(postgresql), archunit.

## 3. 데이터 모델 — Flyway `V1__incident_core.sql`

```sql
-- V1: operation-service 자체 DB(lemuel_operation) — 인시던트 코어
--
-- 인시던트는 Alertmanager 알람(Phase 1) / 자체 이상 탐지(Phase 3) / 수동 등록의
-- 공통 라이프사이클 컨테이너. correlation_key 로 중복 알람을 1건에 병합한다.

CREATE TABLE incidents (
    id                BIGSERIAL PRIMARY KEY,
    correlation_key   VARCHAR(128) NOT NULL,   -- Alertmanager fingerprint (source별 유일 식별자)
    source            VARCHAR(20)  NOT NULL,   -- ALERTMANAGER / ANOMALY / MANUAL
    category          VARCHAR(30)  NOT NULL,   -- SignalCategory (아래 4.2)
    severity          VARCHAR(10)  NOT NULL,   -- CRITICAL / WARNING / INFO
    status            VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    title             VARCHAR(200) NOT NULL,   -- alertname
    description       TEXT,                    -- annotations.summary + description
    service           VARCHAR(50),             -- labels.component (원본 보존)
    labels            JSONB        NOT NULL DEFAULT '{}',
    annotations       JSONB        NOT NULL DEFAULT '{}',
    first_seen_at     TIMESTAMPTZ  NOT NULL,   -- alert startsAt
    last_seen_at      TIMESTAMPTZ  NOT NULL,   -- 마지막 firing 수신 시각
    occurrence_count  INTEGER      NOT NULL DEFAULT 1,  -- firing 반복 수신 횟수
    acknowledged_at   TIMESTAMPTZ,
    acknowledged_by   VARCHAR(100),
    resolved_at       TIMESTAMPTZ,
    resolved_by       VARCHAR(100),            -- 'alertmanager' = 자동 해제
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version           BIGINT       NOT NULL DEFAULT 0,  -- @Version (운영자 조작 경쟁 방지)

    CONSTRAINT chk_incident_status
        CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'FALSE_POSITIVE')),
    CONSTRAINT chk_incident_severity
        CHECK (severity IN ('CRITICAL', 'WARNING', 'INFO')),
    CONSTRAINT chk_incident_source
        CHECK (source IN ('ALERTMANAGER', 'ANOMALY', 'MANUAL'))
);

-- ★ 핵심 불변식: 같은 correlation_key 의 "활성" 인시던트는 최대 1건.
--   resolved 후 재발(firing)은 새 인시던트로 생성된다 (reopen 없음 — 6.3 참조).
CREATE UNIQUE INDEX uq_incident_active
    ON incidents (source, correlation_key)
    WHERE status IN ('OPEN', 'ACKNOWLEDGED');

-- 목록 조회 (상태/카테고리 필터 + 최신순)
CREATE INDEX idx_incident_status_category
    ON incidents (status, category, last_seen_at DESC);

-- 요약 통계 (기간 필터)
CREATE INDEX idx_incident_first_seen ON incidents (first_seen_at);

CREATE TABLE incident_timeline (
    id           BIGSERIAL PRIMARY KEY,
    incident_id  BIGINT      NOT NULL REFERENCES incidents(id),
    event_type   VARCHAR(30) NOT NULL,  -- OPENED/REFIRED/ACKNOWLEDGED/RESOLVED/AUTO_RESOLVED/FALSE_POSITIVE/COMMENT
    actor        VARCHAR(100) NOT NULL, -- 운영자 username 또는 'alertmanager'
    note         TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_timeline_event_type CHECK (event_type IN
        ('OPENED','REFIRED','ACKNOWLEDGED','RESOLVED','AUTO_RESOLVED','FALSE_POSITIVE','COMMENT'))
);

CREATE INDEX idx_timeline_incident ON incident_timeline (incident_id, created_at);
```

설계 결정:

- **REFIRED 타임라인 억제**: Alertmanager `repeat_interval` 마다 firing 이 재수신된다(critical 5m). REFIRED 는 `occurrence_count`/`last_seen_at` 갱신만 하고, 타임라인 행은 **직전 REFIRED 로부터 30분 경과 시에만** 기록한다 (타임라인 폭주 방지).
- **audit_logs**: 운영자 조작(ack/resolve)은 shared-common audit 경로를 태우되, Phase 1 은 `incident_timeline` 이 사실상 감사 이력을 겸한다. 별도 audit_logs 테이블은 loan V5 패턴을 그대로 복사해 두고(테이블만 생성) 컴플라이언스 요건 발생 시 활성화.

## 4. 도메인 모델

### 4.1 Incident 상태머신 (기존 컨벤션 — `canTransitionTo()` 강제)

```
OPEN ──────────→ ACKNOWLEDGED ──→ RESOLVED
  │                   │
  ├──→ RESOLVED       └──→ FALSE_POSITIVE
  └──→ FALSE_POSITIVE
```

- 터미널: `RESOLVED`, `FALSE_POSITIVE` (재전이 불가).
- 자동 해제(alertmanager resolved 수신)는 `OPEN`/`ACKNOWLEDGED` 어느 쪽에서도 `RESOLVED` 로 전이 가능. `resolved_by='alertmanager'`, 타임라인 `AUTO_RESOLVED`.
- **reopen 없음**: 해제된 알람이 재발화하면 partial unique index 가 비어 있으므로 **새 인시던트**가 생성된다. 재발 이력은 `correlation_key` 로 묶어 조회한다(단순성 우선 — reopen 상태머신은 flapping 알람에서 복잡도만 키움).

### 4.2 SignalCategory (11종)

| enum | 의미 | Phase 1 유입 경로 |
|---|---|---|
| `ORDER_FAILURE` | 주문 실패 | (Phase 2 이벤트) |
| `PAYMENT_FAILURE` | 결제/환불 실패 | alert `component: refund` |
| `STOCK_SHORTAGE` | 재고 부족 | (Phase 2 이벤트) |
| `SHIPPING_DELAY` | 배송 지연 | (Phase 2 이벤트) |
| `SETTLEMENT_FAILURE` | 정산 실패 | `settlement-batch`, `settlement-adjustment`, `cashflow-report` |
| `KAFKA_BACKLOG` | Kafka 적체/이벤트 파이프라인 | `kafka-consumer`, `outbox`, `settlement-projection` |
| `REDIS_FAILURE` | Redis 장애 | (Phase 2 exporter) |
| `DB_DEADLOCK` | DB 데드락/커넥션 | `database` |
| `API_TIMEOUT` | API 지연/에러율 | `http` |
| `INFRA_ETC` | 기타 인프라 | `jvm`, `application` |
| `UNKNOWN` | 매핑 실패 | 매핑 누락 라벨 (경고 로그) |

매핑은 코드 하드코딩이 아니라 **application.yml 외부화** — 알람 룰이 늘 때 재배포 없이 대응:

```yaml
app:
  ops:
    category-mapping:            # Alertmanager labels.component → SignalCategory
      settlement-batch: SETTLEMENT_FAILURE
      settlement-adjustment: SETTLEMENT_FAILURE
      cashflow-report: SETTLEMENT_FAILURE
      refund: PAYMENT_FAILURE
      kafka-consumer: KAFKA_BACKLOG
      outbox: KAFKA_BACKLOG
      settlement-projection: KAFKA_BACKLOG
      http: API_TIMEOUT
      database: DB_DEADLOCK
      jvm: INFRA_ETC
      application: INFRA_ETC
    default-category: UNKNOWN
```

## 5. Alertmanager Webhook 연동

### 5.1 수신 페이로드 (Alertmanager webhook v4 — v0.27 기준)

```json
{
  "version": "4",
  "groupKey": "{}:{alertname=\"OutboxPendingBacklog\", severity=\"warning\"}",
  "status": "firing",
  "receiver": "operation-webhook",
  "groupLabels": { "alertname": "OutboxPendingBacklog", "severity": "warning" },
  "commonLabels": { "component": "outbox", "job": "lemuel" },
  "commonAnnotations": { "summary": "Outbox PENDING 적체" },
  "alerts": [
    {
      "status": "firing",
      "fingerprint": "b4f7a2c31d9e0a55",
      "labels": { "alertname": "OutboxPendingBacklog", "severity": "warning", "component": "outbox" },
      "annotations": { "summary": "...", "description": "...", "runbook_url": "..." },
      "startsAt": "2026-07-06T05:12:00Z",
      "endsAt": "0001-01-01T00:00:00Z"
    }
  ]
}
```

처리 단위는 그룹이 아니라 **`alerts[]` 의 개별 alert** — fingerprint 가 correlation_key.

### 5.2 처리 로직 (IngestAlertService)

```
alert (status, fingerprint, labels, annotations, startsAt) 마다:

firing:
  활성 인시던트 조회 (source=ALERTMANAGER, correlation_key=fingerprint,
                      status IN (OPEN, ACKNOWLEDGED))
  ├─ 없음 → INSERT (OPEN, category=mapping(labels.component), severity=labels.severity)
  │         + timeline OPENED
  │         ※ 동시 그룹 알림 경쟁 시 uq_incident_active 위반
  │           → DataIntegrityViolation catch → 재조회 후 갱신 경로로 폴백 (멱등)
  └─ 있음 → last_seen_at=now, occurrence_count+1
            + severity 상향만 반영 (WARNING→CRITICAL 승격 시 timeline 기록, 하향 무시)
            + timeline REFIRED (30분 억제 규칙 — 3장)

resolved:
  활성 인시던트 조회
  ├─ 없음 → no-op (이미 운영자가 닫았거나 유실 — DEBUG 로그만)
  └─ 있음 → RESOLVED 전이, resolved_by='alertmanager', resolved_at=alert.endsAt
            + timeline AUTO_RESOLVED
```

- **멱등성**: repeat_interval 재전송·Alertmanager 재시작 후 재발사 모두 위 로직이 자연 멱등 (INSERT 경로만 unique index + catch 폴백으로 방어).
- **트랜잭션**: alert 1건 = 1 트랜잭션. 한 건 실패가 배치 전체(webhook 응답 5xx → Alertmanager 재시도)를 막지 않도록 per-alert try-catch 후 실패 건수만 로그. 응답은 항상 200 (Alertmanager 재시도 폭주 방지 — 유실 리스크는 repeat_interval 재전송이 보상).

### 5.3 인증

Alertmanager 는 임의 커스텀 헤더를 지원하지 않으므로 기존 `X-Internal-Api-Key` 컨벤션 대신 **Bearer 토큰**을 쓴다. 시크릿 값 자체는 기존 `INTERNAL_API_KEY` 를 재사용:

```yaml
# monitoring/alertmanager.yml — receiver 추가
receivers:
  - name: 'operation-webhook'
    webhook_configs:
      - url: 'http://operation-service:8080/api/ops/webhook/alertmanager'
        send_resolved: true            # ★ 자동 해제의 필수 조건
        http_config:
          authorization:
            type: Bearer
            credentials: '${INTERNAL_API_KEY}'
```

```yaml
# 라우팅 — 기존 Slack 라우팅을 유지하면서 모든 알람을 웹훅에도 전달
route:
  receiver: 'slack-alerts'
  routes:
    - receiver: 'operation-webhook'   # ★ 최상단: 전체 매치 + continue
      continue: true                  #   → 이후 기존 critical/warning 라우팅 계속 평가
    - match: { severity: critical }
      receiver: 'slack-critical'
      ...
```

- 서비스 쪽 `OpsWebhookAuthFilter`: `/api/ops/webhook/**` 경로에서 `Authorization: Bearer <INTERNAL_API_KEY>` 검증. 미설정 시 order 의 `InternalApiKeyFilter` 와 동일 시맨틱(개발: 경고 로그 + 통과).
- alertmanager.yml 은 `${INTERNAL_API_KEY}` envsubst 선처리가 필요 — 기존 `alertmanager.practice.yml` 주석의 Slack 연동 절차와 동일한 방식.

## 6. API 스펙

베이스: `/api/ops` · 인증: JWT `ROLE_ADMIN` (shared-common SecurityConfig) · webhook 만 예외(5.3).

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/ops/webhook/alertmanager` | Alertmanager 수신 (Bearer internal key) |
| GET | `/api/ops/incidents` | 목록 (필터+페이징) |
| GET | `/api/ops/incidents/{id}` | 단건 + 타임라인 |
| POST | `/api/ops/incidents/{id}/ack` | 확인 처리 |
| POST | `/api/ops/incidents/{id}/resolve` | 수동 해제 |
| POST | `/api/ops/incidents/{id}/false-positive` | 오탐 처리 |
| POST | `/api/ops/incidents/{id}/comments` | 코멘트 추가 |
| GET | `/api/ops/incidents/summary` | 대시보드 요약 카운트 |

### 6.1 GET /api/ops/incidents

Query: `status?`, `category?`, `severity?`, `from?`, `to?` (first_seen_at 기준, ISO-8601), `page=0`, `size=20` (max 100), 정렬 고정 `last_seen_at DESC`.

```json
{
  "content": [
    {
      "id": 42,
      "correlationKey": "b4f7a2c31d9e0a55",
      "source": "ALERTMANAGER",
      "category": "KAFKA_BACKLOG",
      "severity": "WARNING",
      "status": "OPEN",
      "title": "OutboxPendingBacklog",
      "service": "outbox",
      "firstSeenAt": "2026-07-06T05:12:00Z",
      "lastSeenAt": "2026-07-06T06:42:00Z",
      "occurrenceCount": 4,
      "acknowledgedBy": null,
      "resolvedBy": null
    }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
}
```

### 6.2 GET /api/ops/incidents/{id}

목록 필드 + `description`, `labels`, `annotations`(runbook_url 포함), `timeline[]`:

```json
{
  "id": 42, "...": "...",
  "annotations": { "summary": "Outbox PENDING 적체", "runbook_url": "https://..." },
  "timeline": [
    { "eventType": "OPENED", "actor": "alertmanager", "note": null, "createdAt": "2026-07-06T05:12:03Z" },
    { "eventType": "ACKNOWLEDGED", "actor": "admin@lemuel", "note": "폴러 재시작 중", "createdAt": "2026-07-06T05:20:11Z" }
  ]
}
```

### 6.3 전이 API (ack / resolve / false-positive)

Request(공통): `{ "note": "선택 메모" }`
동작: `canTransitionTo` 검증 → 실패 시 `409 Conflict` + 현재 상태 포함. actor 는 JWT principal. `@Version` 충돌 시 `409`.
Response: 갱신된 단건(6.2 형식).

### 6.4 GET /api/ops/incidents/summary

Query: `window=24h` (기본, `1h|24h|7d`). 대시보드 헤더/AI 브리핑(Phase 4)의 공용 재료.

```json
{
  "window": "24h",
  "openTotal": 3,
  "byStatus": { "OPEN": 3, "ACKNOWLEDGED": 1, "RESOLVED": 12, "FALSE_POSITIVE": 2 },
  "byCategory": { "SETTLEMENT_FAILURE": 2, "KAFKA_BACKLOG": 5, "API_TIMEOUT": 1 },
  "bySeverity": { "CRITICAL": 1, "WARNING": 6, "INFO": 0 },
  "mttrSeconds": 1840
}
```

`mttrSeconds` = window 내 RESOLVED 건의 `avg(resolved_at - first_seen_at)` — Phase 1 부터 뽑을 수 있는 유일한 "운영 품질" 지표라 포함.

## 7. 인프라 변경점

### 7.1 docker-compose.yml

```yaml
  # ★ DB-per-service: operation-service 전용 PostgreSQL
  operation-postgres:
    image: postgres:17-alpine
    container_name: lemuel-operation-db
    environment:
      POSTGRES_DB: lemuel_operation
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    ports:
      - "127.0.0.1:5439:5432"        # 5433(opslab)/5435(loan)/5436(settlement)/5437(financial)/5438(company) 다음 슬롯
    volumes:
      - operation-db-data:/var/lib/postgresql/data
    healthcheck: { test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER}"], interval: 5s, timeout: 3s, retries: 10 }

  operation-service:
    build: { context: ., args: { MODULE: operation-service } }
    image: ghcr.io/myoungsoo7/lemuel-operation:latest
    container_name: lemuel-operation
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_DATASOURCE_URL: jdbc:postgresql://operation-postgres:5432/lemuel_operation
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      INTERNAL_API_KEY: ${INTERNAL_API_KEY:-lemuel-internal-dev-key}   # webhook Bearer 검증
      JWT_SECRET: ${JWT_SECRET}
    ports:
      - "127.0.0.1:8092:8080"        # 8082/8084/8086/8088/8090 은 기존 서비스가 사용
    depends_on:
      operation-postgres: { condition: service_healthy }
```

- alertmanager 서비스에 `INTERNAL_API_KEY` envsubst 절차 반영 (5.3).
- gateway 환경변수 `OPERATION_SERVICE_URI: http://operation-service:8080` 추가.

### 7.2 gateway-service

```yaml
- id: operation-service
  uri: ${OPERATION_SERVICE_URI:http://localhost:8092}
  predicates:
    - Path=/api/ops/**
```

webhook 경로도 이 라우트에 포함되지만, Alertmanager 는 compose 내부에서 직접 호출하고 외부 유입은 `OpsWebhookAuthFilter` 가 Bearer 검증으로 차단하므로 별도 라우트 분리는 하지 않는다.

### 7.3 prometheus.yml + alert-rules.yml — 관제의 관제

- scrape job 에 operation-service 추가 (`/actuator/prometheus`).
- 알람 룰 1개 추가 — **operation-service 가 죽으면 인시던트 시스템 자체가 침묵**하므로 이것만은 Prometheus 가 직접 감시:

```yaml
  - name: lemuel_operation_alerts
    rules:
      - alert: OperationServiceDown
        expr: up{job="operation"} == 0
        for: 1m
        labels: { severity: critical, component: operation }
        annotations:
          summary: "operation-service 다운 — 인시던트 수집 중단"
          description: "webhook 수신이 멈춥니다. Alertmanager 재전송(repeat_interval)으로 복구 후 보상되지만 즉시 확인 필요."
```

(이 알람의 component=operation 은 category-mapping 에 없으므로 UNKNOWN 으로 적재 — 자기 자신이 죽었다 살아나면 스스로 인시던트가 남는 구조.)

## 8. 테스트 전략 (기존 순서: 도메인 → 서비스 → 컨트롤러 → 통합)

| 레벨 | 대상 | 핵심 케이스 |
|---|---|---|
| 도메인 | `IncidentTest` | 상태머신 전 조합(허용/차단), 터미널 재전이 차단, severity 승격 규칙 |
| 서비스 | `IngestAlertServiceTest` (Mockito) | firing 신규/기존/resolved no-op, category 매핑·UNKNOWN 폴백, REFIRED 30분 억제 |
| 컨트롤러 | `IncidentControllerTest`, `AlertmanagerWebhookControllerTest` (webmvc-test) | 인증(ADMIN 403/Bearer 401), 409 응답, 페이로드 역직렬화(v4 실물 JSON 픽스처) |
| 통합 | Testcontainers PG | ① webhook firing→ack→resolved 전체 흐름 ② **동시 webhook 2건 경쟁 → uq_incident_active 폴백으로 1건만 생성** ③ repeat 멱등 |
| 아키텍처 | `OperationArchitectureTest` (ArchUnit) | loan 패턴 복사 — domain 의 adapter 의존 0, port 방향 검증 |

## 9. 구현 순서 체크리스트

1. [ ] settings.gradle.kts + operation-service 모듈 골격 + `OperationServiceApplication` 부팅 확인
2. [ ] Flyway V1 + JPA 엔티티/리포지토리 + 도메인 모델(상태머신) — 도메인 테스트 선행
3. [ ] IngestAlertService + webhook 컨트롤러 + OpsWebhookAuthFilter — v4 픽스처 테스트
4. [ ] Incident 관리 API (조회/전이/코멘트/요약) + SecurityConfig
5. [ ] ArchUnit + Testcontainers 통합 테스트
6. [ ] compose (operation-postgres, operation-service) + gateway 라우트
7. [ ] alertmanager.yml receiver/route + envsubst 절차 + prometheus scrape/알람 룰
8. [ ] E2E 수동 검증: 임의 알람 발화(예: outbox 적체 유도 또는 amtool alert add) → 인시던트 생성 → ack → 알람 해제 → AUTO_RESOLVED 확인

## 10. Definition of Done

- `docker compose up -d` 후 Alertmanager 알람 발화 시 30초 내 인시던트 자동 생성, 해제 시 AUTO_RESOLVED.
- 게이트웨이 경유 `/api/ops/incidents` 가 ADMIN JWT 로만 접근 가능.
- 동시 webhook 경쟁에서도 활성 인시던트 중복 0 (통합 테스트로 증명).
- `./gradlew :operation-service:test` 전체 통과 + ArchUnit 통과.
- CLAUDE.md / README 서비스 표에 operation-service 추가.

## 11. Phase 2 를 위한 선점 사항 (Phase 1 에 반영해 두는 것)

- `IncidentSource.ANOMALY` / `MANUAL` enum 값 — Phase 3 탐지가 같은 테이블·같은 상태머신을 재사용.
- `SignalCategory` 11종 전체 — Phase 2 이벤트 카테고리(ORDER_FAILURE 등)가 코드 변경 없이 붙음.
- summary API 의 window 파라미터 — Phase 4 일일 브리핑이 동일 쿼리 재사용.

## 12. 구현 노트 (설계 대비 변경분)

- **포트**: financial(8086)·company(8090) 서비스가 병행 추가되어 **앱 8092 / DB 호스트 5439** 로 확정
  (로컬 관리 포트 8093).
- **Flyway V3 추가**: 루트 스캔이 shared-common `AuditLogJpaEntity` 를 포함하므로 `audit_logs` 테이블
  생성 필요 (loan V5 와 동일 — ddl-auto=validate 정합).
- **동시 경쟁 재시도 확대**: uq_incident_active 위반(이중 INSERT)뿐 아니라 겹친 refire 의
  **낙관적 락 충돌(OptimisticLockingFailure)도 재시도 대상** — 최대 5회, 매 시도가 새 트랜잭션으로
  최신 상태를 재조회해 refire 로 수렴 (통합 테스트 시나리오3으로 증명).
- **의존성 슬림화**: bucket4j/mapstruct/kafka/querydsl 은 Phase 1 에서 제외 (shared-common 전이
  의존으로 런타임 충족). ArchUnit 은 1.4.1 (1.3.0 은 Java 25 클래스 파싱 불가).
- **webhook 필터 경로 매칭**: `getServletPath()` 가 아닌 `getRequestURI()` 기준 (MockMvc 등에서
  servletPath 가 빈 문자열인 환경 호환).
- **목록 조회**: JPQL `:param IS NULL OR` 패턴은 PG 에서 bytea 오류 — Specification 으로 동적 조립.

---

# operation-service Phase 2a — 신호 수집 기반 (signal BC)

> 상태: **구현 완료** (2026-07-07). Phase 2 를 두 조각으로 나눈 첫 조각 —
> operation-service 내부에서 완결되는 신호 수집 기반. 교차 서비스 실패 이벤트(2b)는 후속.

## 2a.1 범위와 분할 근거

Phase 2 설계(채널 A 실패 이벤트 + B Prometheus 폴링)는 order/settlement 를 건드리는 큰 교차 변경이라,
리뷰 가능성·안전성을 위해 둘로 나눴다:

| 조각 | 내용 | 경계 |
|---|---|---|
| **2a (본 구현)** | `signal` BC: `ops_metric_bucket` + 5분 버킷 UPSERT + 기존 성공 이벤트 구독(분모) + Prometheus 폴링(인프라 게이지) + exporter | operation-service 내부 + compose only |
| **2b (후속)** | order/settlement 에 실패 이벤트 신설(`lemuel.ops.*.failed`) + operation 이 구독해 `count_signal`(분자) 적재 | 교차 서비스 |

2a 만으로도 인프라 신호(Kafka lag/Redis/DB deadlock/HTTP)와 비즈니스 시도량(분모)이 5분 버킷에 쌓여
Phase 3 이상 탐지의 시계열 기반이 완성된다. 실패율의 분자는 2b 에서 채운다.

## 2a.2 데이터 모델 — Flyway `V4__signal_metric_bucket.sql`

`ops_metric_bucket (metric_key, bucket_start)` PK. 두 신호 유형을 한 테이블로 통합:
- **카운터**(Kafka 이벤트): `count_total`(시도=분모) / `count_signal`(실패=분자) → failure_rate = signal/total
- **게이지**(Prometheus): `value_sum`/`value_max`/`sample_count` → average = sum/count

적재는 네이티브 `INSERT ... ON CONFLICT (metric_key, bucket_start) DO UPDATE` 원자 UPSERT —
동시 다중 컨슈머/폴러가 같은 버킷에 몰려도 앱 락 없이 누적된다.

## 2a.3 채널 A 분모 — 기존 성공 이벤트 구독

`DomainEventSignalConsumer` 가 이미 흐르는 `lemuel.order.created`/`payment.captured`/`settlement.created` 를
operation 전용 컨슈머 그룹(`lemuel-operation`)으로 구독해 `count_total` 만 올린다(signal=false).
신규 발행 비용 0, settlement/loan 소비 무영향(그룹 독립), `auto-offset-reset=latest`(과거 재적재 불필요).

## 2a.4 채널 B — Prometheus 인스턴트 쿼리 폴링

`MetricPollingScheduler`(@Scheduled fixedDelay) → `MetricPollingService` → `PrometheusMetricSourceAdapter`
(RestClient `GET /api/v1/query`). metric_key→PromQL 은 yml 외부화(`app.ops.prometheus.queries`):
`kafka.lag.max`, `redis.up`, `db.deadlock.rate`, `http.error.ratio`. 결과 벡터 첫 샘플을 게이지 버킷에 적재.
한 쿼리 실패가 나머지를 막지 않고, 결과 부재·NaN/Inf 는 empty 로 정규화해 건너뛴다.
`enabled=false`(로컬/테스트 기본) 시 `NoOpMetricSourceAdapter` 주입 + 스케줄러 미기동 → Prometheus 불필요.

## 2a.5 인프라

- compose: `postgres-exporter`(9187, `pg_stat_database_deadlocks`), `redis-exporter`(9121, `redis_up`) 추가.
  operation-service 에 `APP_KAFKA_ENABLED=true`, `OPS_PROMETHEUS_ENABLED=true`, `OPS_PROMETHEUS_URL` 주입.
- prometheus.yml: `operation`/`postgres-exporter`/`redis-exporter` scrape job 추가.

## 2a.6 구현 노트 (설계 §3.1 대비 편차)

- **통계 버킷에 per-event 멱등(processed_events) 미적용** — 고volume 성공 이벤트마다 멱등 행을 쌓으면
  테이블이 무한 팽창하고, 5분 집계는 at-least-once 재전송에 강건하다(드문 중복이 카운트를 거의 못 흔듦).
  Phase 3 판정도 상대임계+z-score 라 소량 노이즈에 둔감. → 컨슈머는 실패해도 ack(통계 손실만 감수,
  컨슈머 정지 방지). 설계 문서는 멱등 적용을 명시했으나 의도적으로 편차를 뒀다.
- **버킷 시각**: 페이로드 파싱 없이 Kafka record timestamp 사용 — 스키마 변화에 견고, 카운트만 필요.
- **RestClient**: Boot4 는 `RestClient.Builder` 자동 빈이 없어 `RestClient.builder()` 로 직접 조립
  (SimpleClientHttpRequestFactory 로 connect/read 타임아웃 지정).

## 2a.7 Phase 2b 를 위한 선점

- 버킷 스키마의 `count_signal` 은 이미 존재 — 2b 실패 이벤트 컨슈머가 `recordEvent(key, true, ts)` 만 부르면
  분자가 채워진다(코드/스키마 변경 0).
- `SignalCategory` 의 ORDER_FAILURE/PAYMENT_FAILURE/STOCK_SHORTAGE/SHIPPING_DELAY 는 2b 실패 이벤트가
  인시던트(ANOMALY source, Phase 3)로 승격될 때 사용.
