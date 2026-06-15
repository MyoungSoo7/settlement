# Architecture Decision Records (ADR)

프로젝트에서 내린 주요 설계 결정들. Michael Nygard 템플릿 기반 (Context / Decision / Consequences / Status).

| # | 제목 | 상태 |
|---|------|------|
| [0001](0001-hexagonal-architecture.md) | Hexagonal Architecture (Ports & Adapters) | Accepted |
| [0002](0002-settlement-state-machine.md) | Settlement 상태 머신 | Accepted |
| [0003](0003-transactional-outbox-pattern.md) | Transactional Outbox 패턴 | Accepted |
| [0004](0004-reverse-settlement-via-adjustment.md) | DONE 정산 불변 + Adjustment 로 역정산 | Accepted |
| [0005](0005-kafka-vs-application-events.md) | Kafka 도입과 ApplicationEvents 공존 | Accepted |
| [0006](0006-resilience4j-tosspg.md) | Toss PG Resilience4j (CB + Retry) | Accepted |
| [0007](0007-daily-reconciliation-and-ledger-invariants.md) | 일일 대사 + 기간 대사 3 불변식 | Accepted |
| [0008](0008-cashflow-report-domain.md) | Cashflow Report 도메인 분리 | Accepted |
| [0009](0009-boot4-migration-module-split.md) | Spring Boot 4.0 모듈 분리 대응 | Accepted |
| [0010](0010-multi-pg-routing-and-bulkhead.md) | 다중 PG 추상화 + Bulkhead 격벽 | Accepted |
| [0011](0011-sku-variant-with-optimistic-lock.md) | ProductVariant (SKU) + Optimistic Lock | Accepted |
| [0012](0012-distributed-tracing-across-outbox.md) | Outbox 경계에서 끊기지 않는 분산 트레이싱 | Accepted |
| [0013](0013-split-payment-with-tenders.md) | 분할결제 + 역순 환불 정책 | Accepted |
| [0014](0014-tier-based-settlement-cycle.md) | SellerTier 기반 T+N 영업일 정산 주기 | Accepted |
| [0015](0015-settlement-holdback-policy.md) | 정산 보류 — 등급별 차등 + 자동 해제 | Accepted |
| [0016](0016-payout-domain-firm-banking.md) | Payout (출금) — 정산 사이클의 종착점 | Accepted |
| [0017](0017-kafka-consumer-dlt-and-replay.md) | Kafka 컨슈머 DLT + 운영자 Replay | Accepted |
| [0018](0018-chargeback-domain.md) | Chargeback (카드사 분쟁) 도메인 | Accepted |
| 0019 | ReversePayout (Payout 완료 후 셀러 환수) | Planned (예약, 파일 없음) |
| [0020](0020-order-settlement-db-split.md) | order ↔ settlement DB 물리 분리 (이벤트 CQRS) | Proposed |

## 규칙

1. 새 ADR 은 번호 증가 순으로. 번호 재사용 금지.
2. 결정이 번복되면 **Superseded by 00XX** 로 상태 변경하고 새 ADR 작성. 구 ADR 삭제 금지 (역사 보존).
3. 주요 결정은 **구현 전** draft 올리고 리뷰 → Accepted. 과거 결정은 retrofit 가능.
