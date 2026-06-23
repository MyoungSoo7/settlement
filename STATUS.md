# STATUS — Lemuel (Settlement)

> 이커머스 주문·결제·정산·선정산대출 MSA 플랫폼 (Spring Boot 4.0 / Java 25 / 헥사고날)

**Last updated:** 2026-06-24

## 현재 상태
- **활성 브랜치:** `develop`
- **구성:** 3 비즈니스 마이크로서비스(order / settlement / loan) + API Gateway + `shared-common` 공유 라이브러리
- **DB:** 서비스별 물리 분리(opslab / settlement_db / lemuel_loan) — DB-per-service 완료
- **최근 커밋:** `c40ed24` fix(frontend): reservation 제거 누락분 정리

## 최근 진척
- **order ↔ settlement DB 물리 분리 완료** (ADR 0020) — settlement 자체 `settlement_db` + 이벤트 드리븐 프로젝션(`settlement_*_view`) + 내부 대사 API `/internal/recon` 으로 cross-DB 연결 0
- **shared-common 버전드 플랫폼 라이브러리화** (ADR 0021) — composite build(includeBuild) + maven-publish, 서비스는 `1.0.0` 좌표 의존
- **loan-service(선정산 대출) 추가** — 자체 DB + 복식부기 원장, settlement 이벤트(`settlement.created/confirmed`)로만 연계하는 상환 saga
- **reservation-service(시공 예약) 제거** — 4서비스 → 3서비스로 정리
- **관리자 시스템 도입** — RBAC(역할/권한) · 메뉴 · 공통코드 관리 (order-service 내 3개 헥사고날 도메인)
- **도메인 견고성 4건** — 환불 PG 실패 롤백/예외변환, 주문 상태머신 도메인화(전이 가드), 결제 승인취소(CANCELED) 전이, PG 대사 승인 시 보정 트리거 이벤트
- **TPS 개선** — PgBouncer, Read Replica 라우팅(opt-in), JDBC 배치, Outbox 비동기 배치 + `FOR UPDATE SKIP LOCKED` 멀티워커, Kafka 컨슈머 병렬화, Redis 2-tier 캐시(opt-in)

## 진행 중
- 신규 도메인(rbac / menu / commoncode) 단위 테스트 보강
- 부하 테스트 확장 — 정산 조회 SLO 측정, 환불 동시성/멱등, 이벤트 수렴 시간(capture→정산)

## 다음 할 일
- [ ] ADR 0022 (이벤트 스키마 레지스트리) 도입 검토
- [ ] PG 대사 승인 이벤트 **소비 핸들러**(DiscrepancyType별 부호 규칙으로 정산 보정) 구현
- [ ] settlement_db 운영 cutover 검증 (`docs/runbook/settlement-db-cutover.md`)
- [ ] 신규 관리자 도메인 통합 테스트 + 권한 가드 검증

## 주요 위험/메모
- settlement 마이그레이션이 2개뿐 — 프로젝션 스키마 중심이므로 cutover 런북 기반 운영 전환 검증 필요
- 외부 `main` 머지가 `develop` 으로 자주 유입 → push 전 `git pull --rebase` 습관화
- 신규 admin 도메인(rbac/menu/commoncode)은 아직 테스트 공백 — 회귀 위험

## 핵심 수치 (2026-06-24 기준)
- 마이그레이션 **70개** (order 63 / settlement 2 / loan 5)
- ADR **21개** (0001~0022, 0019 결번)
- 테스트 클래스 **159개** (+ Testcontainers 통합테스트 13개)
- k6 부하 테스트 시나리오 **4종**

## 참고 문서
- `README.md` — 프로젝트 구조 및 개요
- `PORTFOLIO.md` — 면접용 1장 요약
- `CLAUDE.md` — 에이전트 운용 가이드 / 아키텍처 컨텍스트
- `HARNESS.md` — Claude Code 개발 하네스 구성
- `docs/adr/` — 아키텍처 결정 기록 21개
