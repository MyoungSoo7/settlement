---
name: projection-view-ops
description: settlement 프로젝션 뷰(settlement_*_view)를 새로 추가·변경하거나, 뷰 데이터 드리프트·누락을 조사하고 백필할 때 로드 (ADR 0020 이벤트 드리븐 CQRS). order 데이터가 settlement 에서 안 보이거나 대사 불일치가 날 때도.
---

# 프로젝션 뷰 확장·운영 (ADR 0020)

settlement 가 order 코드·DB 에 의존하지 않고 Order/Payment/User/Product 를 읽는 핵심 패턴.
**하드스톱**: 뷰는 Kafka 이벤트로만 적재한다 — order import·cross-DB 조인으로 "간단히"
채우는 순간 MSA 경계가 무너진다 (ArchUnit + 가드가 차단).

## 구성 요소 지도

| 역할 | 위치 |
|---|---|
| 뷰 엔티티·리포지토리 | `settlement-service/.../settlement/adapter/out/readmodel/` (`Settlement{Order,Payment,User,Product}View*`) |
| 적재 컨슈머 | `settlement-service/.../settlement/adapter/in/kafka/` (`*EventKafkaConsumer`, `*ViewConsumer`) |
| 백필 | `order-service/.../projectionbackfill/` (`SettlementProjectionBackfillController/Service`) |
| 대사 | settlement `recon/OrderReconClient` ↔ order `recon/InternalReconController` (`X-Internal-Api-Key`) |
| 관측 | `SettlementProjectionGauges`·`SettlementProjectionMetrics` (lag·적재 게이지) |

## 신규 뷰 추가 순서

1. **Flyway** — settlement 자체 DB(`settlement_db`)에 `settlement_{대상}_view` 테이블
   (`V{timestamp}__` 명명). order DB 에는 아무것도 만들지 않는다.
2. **readmodel** — `adapter/out/readmodel/` 에 JPA 엔티티+리포지토리 (기존 4개 뷰 패턴 복제).
3. **컨슈머** — `adapter/in/kafka/` 에 적재 컨슈머. 첫 줄 멱등 체크(`idempotency-and-events`),
   upsert 방식(이벤트 재전달·리플레이에 안전).
4. **이벤트 계약** — 소스 토픽이 신규면 `event-contract-change` 스킬 절차 선행.
5. **백필** — order `projectionbackfill` 에 신규 뷰 백필 경로 추가 (초기 적재·유실 복구용).
6. **대사** — order `/internal/recon` 집계와 settlement 측 대조 항목 추가
   (양측이 자기 DB 만 읽는다 — cross-DB 0 유지).
7. **관측** — 프로젝션 게이지에 신규 뷰 등록 (드리프트를 침묵시키지 않기).

## 드리프트·누락 조사 (증상 → 진입점)

| 증상 | 진입점 |
|---|---|
| 뷰 건수 ≠ order 원본 | `/recon-check` 커맨드 + `recon-playbook` (원인 분류 트리) |
| 컨슈머 lag·적체 | `incident-runbooks` (프로젝션 lag 절) + MCP `projection_status` |
| 특정 구간 유실 | order 백필 API 로 구간 재적재 → 대사 재실행으로 수렴 확인 |
| 리플레이 후 중복 의심 | 멱등 3단(`idempotency-and-events`) — upsert 뷰는 중복에 무해해야 정상 |

## 완료 검증

- [ ] settlement 테스트 + (필요 시) Testcontainers 통합 (`settlement-integration-test`)
- [ ] ArchUnit 통과 — settlement→order 의존 0 유지
- [ ] 백필 → 대사 PASS 로 초기 적재 수렴 확인
