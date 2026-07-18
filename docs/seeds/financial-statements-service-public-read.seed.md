# Seed — financial-statements-service 재무제표 공개조회 as-is 사양

> **상태: CONFIRMED** (2026-07-19) · 정본 데이터: [`financial-statements-service-public-read.seed.yaml`](./financial-statements-service-public-read.seed.yaml)
> Ouroboros 방법론(Interview → Seed)으로 결정화.

## Goal (한 줄)

**financial-statements-service(재무제표 공개조회 — 5개 비율 도메인 계산·DART 수집·PER/PBR 미계산 경계)의
현행 동작을 실행 가능한 게이트에 매핑된 불변 사양으로 결정화한다.**

## 범위

| 포함 | 제외 |
|------|------|
| 재무제표 도메인 (6계정·5비율·균형 검증) | audit 내부 |
| DART 수집 (sync·스케줄) | |
| 공개/관리 표면 | |

## 핵심 불변식 (as-is, 파일:라인 근거)

1. **5개 비율은 저장하지 않고 도메인이 계산** (SSOT) — 영업이익률·순이익률·부채비율·자기자본비율·ROA, 공통 `ratio()` 가 분자/분모 null·분모 0 → **null(N/A) 반환, 예외 금지**. 부채비율은 자본잠식(≤0) 별도 가드 (`FinancialStatement.java:65-111`). DB 엔 6계정만 (V1:25-30).
2. **균형 검증** — `isBalanced`: |자산−(부채+자본)| ≤ 자산×허용오차, null 있으면 false.
3. **DART 수집** — 000/013/예외 3분기, XXE 방어, CFS 우선. 개별 기업 실패는 집계 후 계속, 150ms 쿼터 보호. **corp_cls Y/K → KOSPI/KOSDAQ 매핑 upsert, 코넥스·기타 스킵** (2026-07-19 코스닥 경로 추가). 스케줄: 주간 재무제표(일 04:00)·월간 기업(1일 05:00), 키 미설정 시 조용히 skip.
4. **수집 멱등** — `(stock_code, fiscal_year, fs_div)` UNIQUE 최종 방어 + find-then-save upsert (V1:34).
5. **PER/PBR 미계산 경계** — 주가·시총 코드 전무. 밸류에이션 조인은 소비측 몫 (스킬 안티패턴 명시와 코드 일치).
6. **보안** — GET 공개 / admin 은 X-Internal-Api-Key (운영 fail-closed) / 나머지 denyAll / gateway 는 공개 경로만.

## 이벤트 계약

**없음 — 위성 서비스.** loan/investment 가 공개 GET 을 HTTP pull.

## 수용 기준 (게이트 매핑)

| AC | 기준 | 게이트 |
|----|------|--------|
| AC-1 | 5비율 공식·null=N/A 정책 일치 | `FinancialStatementTest` 경계 전수 |
| AC-2 | PER/PBR·주가 코드 0건 유지 | guard.mjs 계열 + 리뷰 |
| AC-3 | 헥사고날 위반 0 | `HexagonalArchitectureTest` |
| AC-4 | LINE ≥ 90% | `:financial-statements-service:jacocoTestCoverageVerification` |
| AC-5 | 재수집 행 증가 0 (upsert) | `FinancialPersistenceAdapterTest` + DB UNIQUE |

## Known Issues (발견만 기록)

- **KI-1 ★DRIFT**: 스킬·SPEC 의 "시드 폴백(코스피20+코스닥10)"은 소멸(커밋 9d38e0ff5 삭제) — 신규 배포 + 키 미설정이면 DB 빈 채 기동. 스킬·SPEC 갱신 필요.
- **KI-2 (해소됨 2026-07-19)**: KOSDAQ 수집 경로 부재였음 — corp_cls Y/K 매핑 upsert 로 수정, 코드·SPEC·스킬 정합 회복.
- **KI-3**: SEED(CFS)·DART(OFS) 별개 행 공존 엣지 (기존 DB 한정).
- **KI-4**: jacoco INSTRUCTION 80% 목록에 `financial.domain` 미포함 (위성 간 비대칭).
- **KI-5**: 스케줄러 silent skip·배치 실패 삼킴·감사 fail-open — 전부 명문화된 by-design.
