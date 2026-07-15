# STATUS — Lemuel (Settlement)

> 이커머스 주문·결제·정산·선정산/기업대출·투자·계정계 + 공개조회 위성(재무제표·경제지표·기업뉴스·시세·공공데이터)·운영관제·AI챗봇 MSA 플랫폼 (Spring Boot 4.0 / Java 25 / 헥사고날)

**Last updated:** 2026-07-15

## 현재 상태
- **활성 브랜치:** `develop` (`main` 은 보호 브랜치 — PR 필수·squash 만·필수 CI 2종)
- **구성:** **12 마이크로서비스** + API Gateway + `shared-common` 공유 라이브러리(버전드 1.0.0)
  - 거래/금융: order(8088) · settlement(8082) · loan(8084) · investment(8100) · account(8102)
  - 공개조회 위성: financial(8086) · economics(8087) · company(8090) · market(8094) · commondata(8098)
  - 부가: operation(8092) · ai(8096)
- **DB:** 12 서비스 모두 물리 분리(DB-per-service) — opslab / settlement_db / lemuel_{loan,financial,economics,company,operation,market,ai,commondata,investment,account}
- **최근 커밋:** `d69741f` chore(settlement): opslab decommission — settlement_schedule_config REVIEW → DROP 확정

## 최근 진척 (2026-06-24 이후)
- **위성·확장 서비스 9종 추가** — financial·economics·company(ADR 0023)·operation·market·ai·commondata·investment·account.
  공개조회 위성은 shared-common 미의존/제한 스캔 + 자체 최소 SecurityConfig(GET 공개, `/admin/**` 는 X-Internal-Api-Key 게이트).
- **금융 계정계 확장** — investment(CEO 투자하기: 투자점수·투자주문) + account(전사 복식부기 GL 집계, 소비 전용) + loan 기업대출(CorporateLoan) + CEO 프론트 메뉴.
- **이벤트 계약-as-code (ADR 0024)** — cross-service 10개 토픽 JSON Schema + 정본 샘플을 `shared-common/testFixtures` 에 단일 출처화, 프로듀서·컨슈머 양방향 계약 테스트로 드리프트 빌드 시점 차단.
- **company↔financial 마스터 통합 (ADR 0025)**, **기업 마스터 일괄등록** 엔드포인트.
- **PG 대사 승인 → 역정산(clawback) 루프 마감** — Discrepancy 승인 소비 핸들러 구현(과거 "다음 할 일" 완료).
- **문서 정비** — 기능명세 `SPEC.md` 추가, 12개 서비스 도메인 규칙 스킬(`*-rules`) + 커맨드 추가, `CLAUDE.md` 에이전트 지침 재구성(SPEC·스킬 위임).
- **k8s postgres 16→17 정합**.
- **operation-service Phase 3 베이스라인 이상탐지** — 신규 `anomaly` BC: `ops_metric_bucket` 실패율 카운터 5종을 5분마다 롤링윈도우 z-score(최소표본·상대임계·정상복귀 게이트)로 판정 → `source=ANOMALY` 인시던트 자동 생성/refire/자동해제. 마이그레이션 0(기존 인시던트 라이프사이클 재사용), 테스트 16건+합성 백테스트, 로컬 실기동 검증 완료 (docs/design/operation-service-phase1.md §Phase 3).

## 진행 중
- account-service 시산표 실검증 + 셀러 payout 현금 유출 GL 인식 (ADR 0026 — 회계 결정 대기)
- operation-service 로드맵: Phase 3 베이스라인 이상탐지 **완료** → 다음은 Phase 4 AI 브리핑
- 커버리지 게이트 LINE 90% 상향 후속 — 신규 서비스 통합테스트 보강

## 다음 할 일
- [ ] ADR 0026 회계 결정 확정 → account payout 현금흐름 인식 구현 + 시산표 실검증
- [ ] payout 파이프라인 실송금 트리거(`PayoutService.requestForSettlement` 호출자) + 셀러 계좌 레지스트리 (그린필드)
- [ ] ADR 0022(이벤트 스키마 레지스트리) 정식 도입 검토 — 현재 계약-as-code(0024)가 경량 선행 단계
- [ ] commondata 실수집 검증 (`DATA_GO_KR_API_KEY` 확보 시)

## 주요 위험/메모
- `DATA_GO_KR_API_KEY` 미보유로 common-data-service 실수집 경로 미검증(소스 등록→조회 전과정은 검증됨)
- 로컬 `bootRun` 은 cwd=모듈 디렉토리라 루트 `.env` 미로딩 → `--args="--JWT_SECRET=... --POSTGRES_*=..."` 주입 필요
- 외부 `main` 머지가 `develop` 으로 유입 → push 전 `git pull --rebase` 습관화
- 운영 배포 필수 주입: 강한 `JWT_SECRET`, `app.security.internal-key-required=true`, 각 서비스 외부 API 키

## 핵심 수치 (2026-07-15 기준 · git-tracked 소스)
> ⚠️ 수치는 `build/`·`.claude/worktrees/` 사본을 **제외한 git ls-files 기준**. 각 줄 끝 명령이 정답 —
> 드리프트 의심 시 명령을 돌려 재검증하고 이 수치를 갱신할 것(휘발성 수치를 명령 없이 손으로 적지 말 것).
- 서비스 **13개** + API Gateway — `git ls-files '*/src/main/resources/application.yml' | wc -l` → 14(=13+gateway)
- Flyway 마이그레이션 **202개** — `git ls-files '*/src/main/resources/db/migration/*.sql' | wc -l` → 202
- ADR **26개** (0001~0027, 0019 결번) — `git ls-files 'docs/adr/[0-9]*.md' | wc -l` → 26
- 테스트 클래스 **568개** (Testcontainers 통합테스트 포함) — `git ls-files '*/src/test/*Test.java' '*/src/test/*Tests.java' '*/src/test/*IT.java' | wc -l` → 568

## 참고 문서
- `SPEC.md` — 전체 기능명세(엔드포인트·도메인 규칙·이벤트 카탈로그)
- `CLAUDE.md` — 에이전트 운용 가이드 / 아키텍처 경계·컨벤션
- `README.md` — 프로젝트 구조 및 개요 · `PORTFOLIO.md` — 면접용 1장 요약 · `HARNESS.md` — 개발 하네스 구성
- `docs/adr/` — 아키텍처 결정 기록 26개 · `*-rules` 스킬 — 서비스별 강제 도메인 규칙
