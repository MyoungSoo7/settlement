# MSA 성숙도 평가 (Lemuel)

- 일자: 2026-06-16
- 대상: order · settlement · reservation · loan + gateway (5 서비스 + API Gateway, 6 Gradle 모듈)
- 검증 기준: `./gradlew build` 전체 통과 (168 스위트 / 880 테스트 / 0 실패), ArchUnit 강제

> 한 줄 요약: **핵심(컨텍스트 분리·코드의존 0·비동기 이벤트·멱등/대사/트레이싱)은 프로덕션 수준으로 제대로 됨.**
> 단 order↔settlement 는 "코드는 MSA, DB는 공유" 중간 상태 — 정합성 우선의 *의도적* 타협이며 ADR 로 근거 기록됨.

## 차원별 성숙도

| # | 차원 | 등급 | 근거 |
|---|---|:---:|---|
| 1 | **Bounded Context 분리** | 🟢 상 | order(거래)·settlement(정산)·reservation(시공예약)·loan(선정산대출) — 책임 경계 명확 |
| 2 | **서비스 간 코드 의존** | 🟢 상 | settlement↔order **0** (Read-only Projection), reservation·loan **0**. ArchUnit(`HexagonalArchitectureTest`/`ReservationArchitectureTest`/`LoanArchitectureTest`)로 CI 강제 |
| 3 | **공유 라이브러리 경계** | 🟢 상 | `shared-common` = 순수 기술 cross-cutting (observability·jwt·ratelimit·outbox·audit·web). 도메인 코드 0 (결제/환불 예외 누수는 order 로 이관 완료) |
| 4 | **통신 방식** | 🟢 상 | 전 구간 **비동기**(Outbox+Kafka), 동기 서비스 호출 0. temporal coupling 회피 |
| 5 | **메시징 신뢰성** | 🟢 상 | 3단 멱등 방어, `FOR UPDATE SKIP LOCKED` 멀티워커 폴러, DLQ + 운영자 Replay |
| 6 | **분산 트레이싱** | 🟢 상 | Outbox `trace_parent` 로 비동기 경계 trace 보존 → 결제→정산→대출 단일 trace |
| 7 | **데이터 정합성(Saga/대사)** | 🟢 상 | 이벤트 + 멱등 + 일/기간 대사 불변식. loan↔settlement 상환 saga |
| 8 | **DB-per-service** | 🟡 중 | reservation_db ✅ / lemuel_loan ✅ / **order+settlement = opslab 공유** ⚠️ (의도적, ADR 0020) |
| 9 | **독립 배포** | 🟡 중 | 컨테이너·게이트웨이 라우팅 분리 ✅, settlement standalone ✅. 단 order+settlement 는 DB·스키마 공유로 데이터 계층 결합 |
| 10 | **빌드 독립성** | 🟠 하 | `shared-common` 모노레포 공동 빌드 → 변경 시 전 서비스 재빌드 락스텝 (ADR 0021 미구현) |
| 11 | **관측성(메트릭/로그)** | 🟢 상 | Micrometer + Prometheus + Tempo + Grafana, 비즈니스 KPI 대시보드 |

## 강점 (제대로 된 부분)

- **코드 경계 100%**: settlement 가 order 를 import 하지 않음 — Read-only Projection(`@Immutable` 엔티티) + ArchUnit 강제
- **이벤트 드리븐 + 신뢰성 풀스택**: Outbox · 3단 멱등 · DLQ/Replay · 대사 · 분산 트레이싱이 *이미* 깔려 있어 서비스가 늘어도 관측/복구 기반 확보
- **정합성 경계 기준 차등 설계**: 결합 강한 order↔settlement 는 공유 DB(강일관성), 결합 약한 reservation·loan 은 DB-per-service + 이벤트 — "MSA 를 위한 MSA" 가 아니라 트레이드오프 기반 결정 (ADR 기록)

## 갭 (개선 여지) — 의도적 타협 vs 미완

| 갭 | 성격 | 비고 |
|---|---|---|
| order+settlement DB 공유(opslab) | **의도적** | 정산 강일관성 우선. 물리 분리는 ADR 0020 Phase 1~5 로 계획 (Phase 0 standalone·Phase 1 이벤트 enrich 착수됨) |
| settlement 자체 Flyway 미소유 | 의도적(공유 DB라) | DB 분리 시 마이그레이션 소유권 이관 (Phase 4) |
| shared-common 배포 락스텝 | 미완 | ADR 0021 — focused 버전드 라이브러리화로 해소 예정 |
| (운영) develop 브랜치 거버넌스 | 프로세스 | origin/develop 에 main 역머지로 옛 구조 혼입 — 아키텍처가 아닌 워크플로우 이슈 |

## 종합 판정

- **"제대로 된 MSA 인가?" → 예 (핵심 기준 충족).** 코드 경계·비동기·신뢰성·관측성은 실무 수준.
- order+settlement 는 **"코드는 MSA, 데이터는 공유"** 의 중간 상태. 이는 결함이 아니라 *정합성 우선의 의도적 선택*이며 전진 경로(ADR 0020)가 명시돼 있음.
- 성숙도 한 단계 상승의 핵심 레버: **order↔settlement DB 물리 분리(ADR 0020 Phase 2~4)** + **shared-common 플랫폼 라이브러리화(ADR 0021)**.

## 로드맵 (완전 MSA 로)

1. **ADR 0020 Phase 2~4** — 이벤트 enrich(Phase 1, 착수) → settlement 로컬 CQRS read model → 읽기 컷오버 → opslab 분리
2. **ADR 0021** — shared-common → focused 버전드 플랫폼 라이브러리 (배포 락스텝 해제)
3. settlement 자체 DB + 자체 Flyway 소유권

## 참조
- [ADR 0001 — Hexagonal Architecture](../adr/0001-hexagonal-architecture.md)
- [ADR 0003 — Transactional Outbox](../adr/0003-transactional-outbox-pattern.md)
- [ADR 0012 — Outbox 경계 분산 트레이싱](../adr/0012-distributed-tracing-across-outbox.md)
- [ADR 0020 — order↔settlement DB 분리](../adr/0020-order-settlement-db-split.md)
- [ADR 0021 — shared-common 플랫폼 라이브러리화](../adr/0021-shared-common-as-platform-library.md)
- [docs/tps.md](../tps.md), [docs/diagrams/architecture.md](../diagrams/architecture.md)
