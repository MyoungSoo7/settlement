# 헥사고날 아키텍처 + MSA 경계

## 전체 구조

```mermaid
graph TB
    subgraph "Client"
        FE[Frontend / Mobile]
    end

    subgraph "Gateway (8080)"
        GW[Spring Cloud Gateway]
    end

    subgraph "order-service (8088)"
        direction TB
        OUser[user]
        OProduct[product + variants]
        OCart[cart]
        OOrder[order + items]
        OPay[payment]
        OShip[shipping]
    end

    subgraph "settlement-service (8082)"
        direction TB
        SSet[settlement]
        SRecon[pg-reconciliation]
        SReport[report PDF]
    end

    subgraph "Infra"
        PG[(opslab<br/>PostgreSQL 17)]
        PG2[(settlement_db<br/>PostgreSQL 17)]
        ES[(Elasticsearch 8.17)]
        K[Kafka / Redpanda]
        TM[Tempo]
        PR[Prometheus]
        GF[Grafana]
    end

    subgraph "External"
        Toss[Toss Payments]
        Kcp[KCP]
        Nice[NICE]
        Inicis[INICIS]
    end

    FE --> GW
    GW --> OOrder
    GW --> OPay
    GW --> SSet

    OPay -.PG Router.-> Toss
    OPay -.PG Router.-> Kcp
    OPay -.PG Router.-> Nice
    OPay -.PG Router.-> Inicis

    OOrder --> PG
    OPay --> PG
    SSet --> PG2
    SSet --> ES

    OPay -- Outbox + traceparent --> K
    K -- Kafka consumer --> SSet

    OOrder -.metrics + traces.-> TM
    SSet -.metrics + traces.-> TM
    PR -.scrape.-> OOrder
    PR -.scrape.-> SSet
    GF --> PR
    GF --> TM
```

## 헥사고날 패키지 의존 방향 (서비스 1개 단면)

```mermaid
graph LR
    subgraph "도메인 (POJO, 프레임워크 의존 0)"
        D[domain.*<br/>Order / Payment / Settlement<br/>비즈니스 규칙 + 상태머신]
    end

    subgraph "애플리케이션 (UseCase)"
        AIN[port.in<br/>인바운드 포트]
        SVC[service<br/>UseCase 구현]
        AOUT[port.out<br/>아웃바운드 포트]
    end

    subgraph "어댑터"
        IN_WEB[adapter.in.web<br/>REST]
        IN_KAFKA[adapter.in.kafka]
        IN_BATCH[adapter.in.batch]
        OUT_PERS[adapter.out.persistence<br/>JPA]
        OUT_EXT[adapter.out.external<br/>PG / Mail]
        OUT_EVT[adapter.out.event<br/>Outbox / Kafka]
    end

    IN_WEB --> AIN
    IN_KAFKA --> AIN
    IN_BATCH --> AIN
    AIN --> SVC
    SVC --> D
    SVC --> AOUT
    OUT_PERS -.implements.-> AOUT
    OUT_EXT -.implements.-> AOUT
    OUT_EVT -.implements.-> AOUT

    style D fill:#fff8dc
    style SVC fill:#e0f2fe
```

**핵심 원칙** (ArchUnit 으로 강제):
1. `domain.*` 은 Spring/JPA 의존 금지 — 순수 POJO
2. `application.service.*` 는 `adapter.out.persistence.*` 직접 의존 금지 — 포트 경유
3. 어댑터끼리는 다른 도메인 영역의 `adapter.out.persistence` 직접 import 금지
4. `application.port.*` 의 `*Port` 는 인터페이스만

## MSA 경계 — settlement-service ↛ order-service 코드 의존 0

```mermaid
graph LR
    subgraph "order-service (소유)"
        OPay[payment.domain.Payment]
        OOrder[order.domain.Order]
        OUser[user.domain.User]
        DB1[(payments / orders / users)]
        OPay --> DB1
        OOrder --> DB1
        OUser --> DB1
    end

    subgraph "settlement-service (자체 DB settlement_db)"
        SView1[settlement_payment_view]
        SView2[settlement_order_view]
        SView3[settlement_user_view]
        SDB[(settlement_db)]
        SDom[Settlement domain]

        SView1 --> SDom
        SView2 --> SDom
        SView3 --> SDom
        SView1 --> SDB
        SView2 --> SDB
        SView3 --> SDB
    end

    DB1 -. Outbox+Kafka 이벤트 .-> KB[lemuel.order.* / payment.* / user.*]
    KB -. 프로젝션 컨슈머 .-> SView1
    KB -. 프로젝션 컨슈머 .-> SView2
    KB -. 프로젝션 컨슈머 .-> SView3

    style SView1 fill:#fef3c7
    style SView2 fill:#fef3c7
    style SView3 fill:#fef3c7
```

**이벤트 드리븐 프로젝션 패턴 (CQRS, ADR 0020)**:
- settlement-service 가 자체 DB(settlement_db)에 소유하는 `settlement_*_view` 프로젝션 테이블에 order 가 발행한 Kafka 이벤트를 적재
- `settlement-service/build.gradle.kts` 에 `implementation(project(":order-service"))` **없음**
- 비즈니스 로직 변경은 양 서비스가 독립 배포 가능
- 대사는 order 내부 API(`/internal/recon`) 호출로 처리 — 양측 모두 자기 DB 만 읽어 cross-DB 연결 0

## 통신 매트릭스

| From | To | 방식 | 동기/비동기 | 보장 |
|------|----|----|-------------|------|
| Client | Gateway | HTTP | 동기 | — |
| Gateway | order/settlement | HTTP | 동기 | Resilience4j |
| order-service | PG (Toss/KCP/NICE/INICIS) | HTTPS | 동기 | CB + Retry per-PG |
| order-service | settlement-service | Kafka (lemuel.payment.*) | 비동기 | Outbox + 멱등 3단 |
| settlement-service | order-service | 내부 대사 API `/internal/recon` (HTTP) | 동기 | 대사 전용, 조회는 이벤트 CQRS 프로젝션으로 대체 |
| order-service | DB (opslab) | JPA / JDBC | 동기 | Optimistic Lock (Variant), Pessimistic (Refund) |
| settlement-service | DB (settlement_db) | JPA / JDBC | 동기 | 자체 DB, Kafka 이벤트 프로젝션 적재 |
| settlement-service | ES | REST | 동기 | 비동기 큐 (settlement_index_queue) |
| order-service | Tempo | OTLP/HTTP | 비동기 | Sampling 1.0 (개발) |
