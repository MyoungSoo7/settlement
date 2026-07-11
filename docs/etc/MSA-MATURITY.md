# MSA 성숙도 평가 (Lemuel)

- 일자: 2026-06-16 (기준선) · order↔settlement DB 물리 분리(ADR 0020) 및 위성 서비스 확장 반영해 갱신
- 대상: order · settlement · loan 등 **12 서비스 + API Gateway** (13 Gradle 모듈 + shared-common composite build). reservation(시공예약) 서비스는 이후 제거됨.
- 검증 기준: `./gradlew build` 전체 통과, ArchUnit 강제

> 한 줄 요약: **핵심(컨텍스트 분리·코드의존 0·비동기 이벤트·멱등/대사/트레이싱)은 프로덕션 수준으로 제대로 됨.**
> order↔settlement 는 ADR 0020 으로 DB 물리 분리까지 완료 — 이벤트 CQRS 프로젝션(settlement_db 소유) + 내부 대사 API 로 코드·DB 의존 0.

## 차원별 성숙도

| # | 차원 | 등급 | 근거 |
|---|---|:---:|---|
| 1 | **Bounded Context 분리** | 🟢 상 | order(거래)·settlement(정산)·loan(선정산·기업대출) + financial·economics·company·operation·market·ai·common-data·investment·account 위성 9종 — 책임 경계 명확 |
| 2 | **서비스 간 코드 의존** | 🟢 상 | settlement↔order **0** (이벤트 CQRS 프로젝션), loan·위성 **0**. ArchUnit(`HexagonalArchitectureTest`/`LoanArchitectureTest` 등)로 CI 강제 |
| 3 | **공유 라이브러리 경계** | 🟢 상 | `shared-common` = 순수 기술 cross-cutting (observability·jwt·ratelimit·outbox·audit·web). 도메인 코드 0 (결제/환불 예외 누수는 order 로 이관 완료) |
| 4 | **통신 방식** | 🟢 상 | 전 구간 **비동기**(Outbox+Kafka), 동기 서비스 호출 0. temporal coupling 회피 |
| 5 | **메시징 신뢰성** | 🟢 상 | 3단 멱등 방어, `FOR UPDATE SKIP LOCKED` 멀티워커 폴러, DLQ + 운영자 Replay |
| 6 | **분산 트레이싱** | 🟢 상 | Outbox `trace_parent` 로 비동기 경계 trace 보존 → 결제→정산→대출 단일 trace |
| 7 | **데이터 정합성(Saga/대사)** | 🟢 상 | 이벤트 + 멱등 + 일/기간 대사 불변식. loan↔settlement 상환 saga |
| 8 | **DB-per-service** | 🟢 상 | 전 서비스 자체 DB ✅ — settlement_db · lemuel_loan · lemuel_financial 등 12종 물리 분리. order↔settlement opslab 공유는 ADR 0020 으로 물리 분리 완료 |
| 9 | **독립 배포** | 🟢 상 | 컨테이너·게이트웨이 라우팅 분리 ✅, settlement standalone(자체 bootJar·settlement_db) ✅. order↔settlement 데이터 계층 결합 해소(ADR 0020) |
| 10 | **빌드 독립성** | 🟢 상 | `shared-common` 을 버전드 내부 라이브러리로 분리(composite build `includeBuild` + maven-publish) — 서비스는 `shared-common:1.0.0` 좌표로 의존 (ADR 0021) |
| 11 | **관측성(메트릭/로그)** | 🟢 상 | Micrometer + Prometheus + Tempo + Grafana, 비즈니스 KPI 대시보드 |

## 강점 (제대로 된 부분)

- **코드 경계 100%**: settlement 가 order 를 import 하지 않음 — 이벤트 CQRS 프로젝션(settlement_db 소유 `settlement_*_view`) + ArchUnit 강제
- **이벤트 드리븐 + 신뢰성 풀스택**: Outbox · 3단 멱등 · DLQ/Replay · 대사 · 분산 트레이싱이 *이미* 깔려 있어 서비스가 늘어도 관측/복구 기반 확보
- **정합성 경계 기준 차등 설계**: order↔settlement 는 이벤트 CQRS 로 DB 분리(ADR 0020)하되 내부 대사 API 로 강정합 대사 유지, loan 등 위성은 DB-per-service + 이벤트 — "MSA 를 위한 MSA" 가 아니라 트레이드오프 기반 결정 (ADR 기록)

## 갭 (개선 여지) — 의도적 타협 vs 미완

| 갭 | 성격 | 비고 |
|---|---|---|
| order+settlement DB 공유(opslab) | ✅ 해소 | ADR 0020 으로 물리 분리 완료 — settlement_db 이벤트 CQRS 프로젝션 + 내부 대사 API |
| settlement 자체 Flyway 미소유 | ✅ 해소 | settlement_db 자체 Flyway 가 스키마 소유 |
| shared-common 배포 락스텝 | ✅ 해소 | ADR 0021 — 버전드 내부 라이브러리(composite build)로 전환 완료 |
| (운영) develop 브랜치 거버넌스 | 프로세스 | origin/develop 에 main 역머지로 옛 구조 혼입 — 아키텍처가 아닌 워크플로우 이슈 |

## 종합 판정

- **"제대로 된 MSA 인가?" → 예 (핵심 기준 충족).** 코드 경계·비동기·신뢰성·관측성은 실무 수준.
- order+settlement 는 ADR 0020 으로 **DB 물리 분리까지 완료** — 이벤트 CQRS 프로젝션 + 내부 대사 API 로 코드·DB 의존 0.
- 초기 성숙도 레버(ADR 0020 DB 분리 · ADR 0021 shared-common 라이브러리화)는 모두 반영됨. 남은 과제는 서비스별 CI/CD·Helm 파이프라인 완전 분리 등 배포 자동화.

## 로드맵 (완전 MSA 로)

1. ✅ **ADR 0020 완료** — 이벤트 CQRS 프로젝션(settlement_db `settlement_*_view`) + 내부 대사 API 로 order↔settlement DB 물리 분리
2. ✅ **ADR 0021 완료** — shared-common → 버전드 내부 라이브러리(composite build), 배포 락스텝 해제
3. ✅ settlement 자체 DB(settlement_db) + 자체 Flyway 소유권 확보

## 참조
- [ADR 0001 — Hexagonal Architecture](../adr/0001-hexagonal-architecture.md)
- [ADR 0003 — Transactional Outbox](../adr/0003-transactional-outbox-pattern.md)
- [ADR 0012 — Outbox 경계 분산 트레이싱](../adr/0012-distributed-tracing-across-outbox.md)
- [ADR 0020 — order↔settlement DB 분리](../adr/0020-order-settlement-db-split.md)
- [ADR 0021 — shared-common 플랫폼 라이브러리화](../adr/0021-shared-common-as-platform-library.md)
- [docs/tps.md](../tps.md), [docs/diagrams/architecture.md](../diagrams/architecture.md)
