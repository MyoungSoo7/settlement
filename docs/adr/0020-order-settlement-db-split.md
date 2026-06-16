# ADR 0020 — order ↔ settlement DB 물리 분리 (이벤트 기반 CQRS 전환)

- 상태: Proposed (구현 전 계획 — Strangler 단계별 승인 후 Accepted 전환)
- 일자: 2026-06-15

> 번호 주의: `0019` 는 ADR 0018 에서 **ReversePayout** 용으로 예약됨. 본 결정은 `0020` 사용.

## 컨텍스트

현재 order-service 와 settlement-service 는 **단일 PostgreSQL(`opslab`) 을 공유**한다.
settlement 는 order 의 데이터를 **Read-only Projection**(`@Immutable` JPA 엔티티가 order 테이블을
직접 매핑)으로 읽어 정산을 만든다. 이 구조는 "코드 의존성 0 + 강한 일관성" 을 싸게 얻는 핵심 설계였고,
2 서비스 규모에서는 합리적이다.

또한 settlement-service 는 현재 **라이브러리 모드**(bootJar 비활성)로 order-service fat jar 에
번들되어 물리적으로 한 배포 단위다 (docker-compose `SETTLEMENT_SERVICE_URI: http://order-service:8080`).

**왜 분리를 검토하나**: 이 프로젝트는 포트폴리오 목적이며, "진짜 이벤트 기반 CQRS MSA(DB-per-service)"
를 끝까지 구현한 사례를 보여주는 것이 목표다. 공유 DB 는 진정한 독립 배포/스케일/장애격리를 막는다.

### settlement 가 order DB 를 읽는 결합 표면 (분리 시 전부 제거 대상)

| 위치 | 현재 (공유 DB 직접 읽기) |
|---|---|
| `SettlementPaymentReadModel` | `payments` 테이블 매핑 |
| `SettlementOrderReadModel` | `orders` 테이블 매핑 |
| `SettlementUserReadModel` | `users` 테이블 (email) |
| `SettlementProductReadModel` | `products` 테이블 (name) |
| `SellerTierJdbcAdapter` | `payments→orders→products→users` 4테이블 native 조인 (등급·주기) |
| ledger / report(cashflow) / pgreconciliation | 결제 데이터 직접 조회 |

## 결정

**빅뱅 전환 금지.** Strangler 패턴으로 **6단계**, 각 단계 독립 배포·롤백 가능하게 전환한다.
정산 *생성* 경로는 **Event-carried State Transfer**(이벤트에 필요한 데이터 동봉)로, 조회/리포팅은
**로컬 복제 read model**(이벤트로 채우는 settlement 소유 테이블)로 처리한다.

### 목표 상태

```
order-service     → opslab DB        (users·orders·payments·products·cart + Outbox)
settlement-service → settlement_db   (settlements·adjustments·payout·ledger·chargeback·
                                       pg대사 + 이벤트로 복제한 read model)
연결: order 도메인 이벤트(Kafka) → settlement 소비 → settlement_db projection 적재
불변식: settlement 는 order 테이블을 직접 매핑/조인하지 않는다 (cross-DB join 0)
```

### Phase 로드맵

| Phase | 목표 | 핵심 변경 | 검증 | 롤백 |
|---|---|---|---|---|
| **0. 기반** | settlement standalone 승격 | bootJar 활성, prod `@SpringBootApplication`, 자체 Flyway 소유, `settlement_db` 프로비저닝, gateway URI 분리 | 단독 부팅 (통합테스트 부트스트랩 보유) | 번들 모드 복귀 |
| **1. 이벤트 자급자족** | 이벤트만으로 정산 생성 가능 | Outbox 페이로드에 amount·sellerId·sellerTier·settlementCycle·productName 동봉 → 4테이블 조인 제거 | 이벤트 기반 정산 vs DB조회 정산 **대사 일치** | 페이로드 무시 |
| **2. 로컬 read model** | CQRS projection 병렬 구축 | order 이벤트 소비 → settlement 소유 테이블 적재. 기존 매핑과 dual-run | projection lag·값 일치율 메트릭 | projection 미사용 |
| **3. 읽기 컷오버** | order 테이블 직접 참조 0 | 모든 조회를 로컬 projection 으로 전환, native opslab SQL 제거 | ArchUnit 규칙: settlement 가 order 테이블 매핑/조인 금지 | 매핑 복귀 |
| **4. 물리 분리** | DB 인스턴스 분리 | settlement 데이터소스 → 별도 PG, 테이블+projection 이전(백필) | order DB 다운 시 settlement 조회/정산 지속(큐 적체만) | 데이터소스 원복 |
| **5. 하드닝** | 운영 안정화 | cross-DB 대사 불변식, Schema Registry(Avro/Protobuf), 파티션 키=aggregateId 순서보장 | DLQ replay·대사 무결성 | — |

### 재사용 자산 (신규 인프라 불필요)

Outbox 프로듀서, 3단 멱등성, Outbox `traceparent` 분산 트레이싱, DLQ + replay, 대사 불변식 —
이벤트 파이프라인이 이미 깔려 있어 그 위에서 DB 분리를 "완성" 하는 형태다.

## 결과

### 좋아지는 점
- 진짜 독립 배포·스케일 (settlement_db 를 집계/리포팅에 맞게 튜닝)
- 장애 격리 (order DB 사고가 settlement 로 전파되지 않음)
- 이벤트 계약 강제 → 이벤트소싱 CQRS MSA 포트폴리오 서사
- 단계별 ADR = 점진·역행가능 마이그레이션 규율 자체가 증빙

### 트레이드오프 / 리스크
- **금융 도메인에 eventual consistency 도입** — 복제 지연으로 stale 데이터 가능 → 정산 생성은 *이벤트 동봉 데이터*만 사용해 lag 의존 제거 + 대사로 흡수
- 모든 projection 을 이벤트 피드로 재구축 (대규모 작업) + dual-write/복제 버그 표면 증가
- 운영비 ↑ (DB 2개, 토픽·스키마 관리)
- 기존 강점 "코드 의존 0 + 강한 일관성" 중 강한 일관성 일부 포기

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| **공유 DB 유지** (현행) | △ (기본값) | 2서비스·강일관성엔 합리적. 단 진정한 독립성 없음 → 포트폴리오 목표 미달 |
| **빅뱅 DB 분리** | ✗ | 금융 도메인에서 정합성 사고 거의 확정. 병렬운영+대사 안전망 없음 |
| **동기 API/gRPC 로 order 조회** | ✗ | 가용성 결합(order 다운 시 정산 정지) — 비동기 분리 취지에 역행 |
| **Strangler 6단계 (본 결정)** | ✓ | 각 단계 검증·롤백 가능, 기존 이벤트 자산 재사용, lag 를 이벤트 동봉으로 회피 |

## 선결 / 순서

1. 분리 우선순위: **reservation·loan 자체 DB (저결합·고이득) ≫ order/settlement (고결합·고비용)**.
   reservation/loan 으로 패턴을 먼저 증명한 뒤 본 전환에 착수 권장.
2. Phase 0(standalone 승격)은 본 전환의 **하드 선결**.

## 후속 (단계별 ADR 골격 — 실행 시 분화 예정)

| 예정 ADR | 범위 |
|---|---|
| 0020-1 | Phase 0 — settlement standalone 승격 (bootJar·Flyway 소유권 이전) |
| 0020-2 | Phase 1 — Outbox 이벤트 페이로드 enrich 계약 |
| 0020-3 | Phase 2~3 — CQRS 로컬 read model + 읽기 컷오버 |
| 0020-4 | Phase 4~5 — 물리 분리 + Schema Registry + cross-DB 대사 |

## 참조

- [0003 — Transactional Outbox 패턴](0003-transactional-outbox-pattern.md)
- [0005 — Kafka vs ApplicationEvents](0005-kafka-vs-application-events.md)
- [0007 — 일일 대사 + 기간 대사 3 불변식](0007-daily-reconciliation-and-ledger-invariants.md)
- [0009 — Spring Boot 4.0 모듈 분리 대응](0009-boot4-migration-module-split.md)
- [0012 — Outbox 경계 분산 트레이싱](0012-distributed-tracing-across-outbox.md)
- [0017 — Kafka 컨슈머 DLT + Replay](0017-kafka-consumer-dlt-and-replay.md)
