# Architecture Decision Records (ADR)

프로젝트에서 내린 주요 설계 결정들. Michael Nygard 템플릿 기반 (Context / Decision / Consequences / Status).

| #                                                               | 제목                                                                                           | 상태     |
| --------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- | -------- |
| [0001](0001-hexagonal-architecture.md)                          | Hexagonal Architecture (Ports & Adapters)                                                      | Accepted |
| [0002](0002-settlement-state-machine.md)                        | Settlement 상태 머신                                                                           | Accepted |
| [0003](0003-transactional-outbox-pattern.md)                    | Transactional Outbox 패턴                                                                      | Accepted |
| [0004](0004-reverse-settlement-via-adjustment.md)               | DONE 정산 불변 + Adjustment 로 역정산                                                          | Accepted |
| [0005](0005-kafka-vs-application-events.md)                     | Kafka 도입과 ApplicationEvents 공존                                                            | Accepted |
| [0006](0006-resilience4j-tosspg.md)                             | Toss PG Resilience4j (CB + Retry)                                                              | Accepted |
| [0007](0007-daily-reconciliation-and-ledger-invariants.md)      | 일일 대사 + 기간 대사 3 불변식                                                                 | Accepted |
| [0008](0008-cashflow-report-domain.md)                          | Cashflow Report 도메인 분리                                                                    | Accepted |
| [0009](0009-boot4-migration-module-split.md)                    | Spring Boot 4.0 모듈 분리 대응                                                                 | Accepted |
| [0010](0010-multi-pg-routing-and-bulkhead.md)                   | 다중 PG 추상화 + Bulkhead 격벽                                                                 | Accepted |
| [0011](0011-sku-variant-with-optimistic-lock.md)                | ProductVariant (SKU) + Optimistic Lock                                                         | Accepted |
| [0012](0012-distributed-tracing-across-outbox.md)               | Outbox 경계에서 끊기지 않는 분산 트레이싱                                                      | Accepted |
| [0013](0013-split-payment-with-tenders.md)                      | 분할결제 + 역순 환불 정책                                                                      | Accepted |
| [0014](0014-tier-based-settlement-cycle.md)                     | SellerTier 기반 T+N 영업일 정산 주기                                                           | Accepted |
| [0015](0015-settlement-holdback-policy.md)                      | 정산 보류 — 등급별 차등 + 자동 해제                                                            | Accepted |
| [0016](0016-payout-domain-firm-banking.md)                      | Payout (출금) — 정산 사이클의 종착점                                                           | Accepted |
| [0017](0017-kafka-consumer-dlt-and-replay.md)                   | Kafka 컨슈머 DLT + 운영자 Replay                                                               | Accepted |
| [0018](0018-chargeback-domain.md)                               | Chargeback (카드사 분쟁) 도메인                                                                | Accepted |
| [0020](0020-order-settlement-db-split.md)                       | order↔settlement DB 물리 분리 (이벤트 프로젝션 CQRS)                                           | Accepted |
| [0021](0021-shared-common-as-platform-library.md)               | shared-common 버전드 플랫폼 라이브러리화                                                       | Accepted |
| [0022](0022-event-schema-registry.md)                           | 이벤트 Schema Registry (Avro + Redpanda SR)                                                    | Proposed |
| [0023](0023-company-service-news-reputation.md)                 | company-service 기업 뉴스·평판                                                                 | Accepted |
| [0024](0024-event-contract-as-code.md)                          | 이벤트 계약-as-code (JSON Schema 양방향 계약 테스트)                                           | Accepted |
| [0025](0025-company-financial-master-unification.md)            | 기업 마스터 단일화 (company ↔ financial)                                                       | Proposed |
| [0026](0026-account-payout-cash-recognition.md)                 | 계정계 payout 현금흐름 인식 + 시산표 실검증 (Option ①)                                         | Accepted |
| [0027](0027-db-partitioning-retention-pk-standard.md)           | DB 파티셔닝·리텐션·PK 전략 표준 + 유지보수 자동화                                              | Accepted |
| [0028](0028-procedural-discipline-plugin-independence.md)       | 절차 규율층 플러그인 독립 내재화 + 이중 라우팅 경계                                            | Accepted |
| [0029](0029-settlement-tax-deliverables.md)                     | 정산 연계 세무 산출물 (부가세·원천징수·세금계산서) — 최초 0027 채택 후 번호 충돌로 0029 재부여 | Accepted |
| [0030](0030-account-materialized-balance-global-nonnegative.md) | 계정계 통제계정 실체화 잔액 + 잔액 인식 라우팅 전역화                                          | Proposed |
| [0031](0031-seller-tier-lifecycle.md)                           | 셀러 등급 라이프사이클 (자동 산정 + 변경 이력 + 강등 유예)                                     | Accepted |
| [0032](0032-effective-dated-commission-rate-policy.md)          | 수수료율 유효기간 정책 (effective-dated + scope 우선순위)                                      | Accepted |
| [0033](0033-telegram-spec-driven-codegen.md)                    | 전문(電文) 스펙 주도 코드 생성 (telegram spec-driven codegen)                                  | Accepted |
| [0034](0034-ai-service-rag-pgvector.md)                         | ai-service RAG 지식베이스 (pgvector + 시스템 프롬프트 증강)                                    | Accepted |
| [0035](0035-kafka-topic-catalog.md)                             | Kafka 토픽 카탈로그 (파티션 수를 코드 안으로 — 키 재해시 차단)                                 | Accepted |
| [0036](0036-receipt-ocr-platform.md)                            | 증빙 OCR 플랫폼화 (도메인 중립 비전 추출 클라이언트 + 법인카드 영수증 3자 대사)                | Accepted |
| [0037](0037-msa-decomposition-rationale.md)                     | MSA 서비스 경계 근거 (사업 전제 + 분해 기준 6축)                                               | Accepted |

> **0019 결번**: 0019 번은 ADR 이 작성된 적이 없다(결번). 문서·코드 어디에도 참조가 없어 유실이 아니라
> 건너뛴 번호로 간주한다. 규칙 1(번호 재사용 금지)에 따라 재할당하지 않는다.

## 규칙

1. 새 ADR 은 번호 증가 순으로. 번호 재사용 금지.
2. 결정이 번복되면 **Superseded by 00XX** 로 상태 변경하고 새 ADR 작성. 구 ADR 삭제 금지 (역사 보존).
3. 주요 결정은 **구현 전** draft 올리고 리뷰 → Accepted. 과거 결정은 retrofit 가능.
