---
name: financial-data-rules
description: 재무제표 공개조회 서비스 규칙 — 5개 비율은 저장 않고 도메인 계산(null=N/A), DART 실수집 전용(샘플 시드 제거됨), GET 공개/admin 게이트, PER/PBR 금지 경계. financial-statements-service 로직 작성·리뷰 시 로드.
---

# 재무제표 데이터 규칙 (financial-statements-service)

코스피·코스닥 상장사 **연간 요약 재무제표 공개 read-only** 서비스(port 8086, lemuel_financial).
loan 기업신용대출·investment 투자점수의 **회계자료 원천(SSOT)**. 타 서비스 코드·DB·이벤트 의존 0.

## 저장 계정 6개 + 파생비율은 계산 (핵심)

- 저장(BigDecimal, 원 단위, NUMERIC(21), 전부 nullable): `revenue, operatingProfit, netIncome,
  totalAssets, totalLiabilities, totalEquity`. 메타: stockCode(6자리)·fiscalYear·fsDivision·source.
- **★ 비율은 DB 컬럼이 아니라 `FinancialStatement` 도메인 메서드 계산값**(SSOT). margin/ratio 컬럼 추가 금지.

| 비율 | 계산식 | 계산불가 시 |
|---|---|---|
| `operatingMargin()` | 영업이익/매출 ×100 | null |
| `netMargin()` | 순이익/매출 ×100 | null |
| `debtRatio()` | 부채총계/자본총계 ×100 | **자본잠식(자본총계≤0)이면 null**(별도 가드) |
| `equityRatio()` | 자본총계/자산총계 ×100 | null |
| `roa()` | 순이익/자산총계 ×100 | null |

- 공통 `ratio()`: null 계정·0 분모면 **null 반환(예외 아님)** → 표시계층 N/A. 반올림 `HALF_UP` 소수 2자리.
  **이 null=N/A 시맨틱을 소비측(loan/investment)이 상속** — 예외로 바꾸면 계약 파괴.
- `isBalanced(tol)`: |자산−(부채+자본)| ≤ 자산×허용오차.
- 생성자 불변식: stockCode 길이=6, fiscalYear 1990~2100, fsDivision·source 필수, currency 기본 KRW.
- `Company`: 정체성은 **stockCode**(equals/hashCode). corpCode(DART 8자리)는 nullable(수집 전 미상, 동기화가 채움).

## enum

- `FsDivision`: `CFS`(연결)/`OFS`(별도) — DART fs_div 와 동일. UNIQUE `(stockCode, fiscalYear, fsDivision)` → 연결/별도 별개 행.
  DART 파싱은 **CFS 계정 있으면 CFS, 없으면 OFS**.
- `StatementSource`: `SEED`(레거시 — 제거된 샘플 시드의 기존 DB 잔존 행 호환용)/`DART`. DART upsert 가 SEED 를 대체.

## DART 수집 (실데이터 전용 — 샘플 시드는 제거됨)

- `DartApiClient`: corpCode.xml(상장만)·company.json(corp_cls Y=코스피/K=코스닥)·fnlttSinglAcnt(reprt_code=11011 연간).
  status `000`=OK, `013`=데이터없음(empty), 그 외 예외. **XXE 방어**(secure processing + disallow-doctype).
- 기업 동기화는 corp_cls **Y→KOSPI / K→KOSDAQ 매핑 upsert**, 코넥스(N)·기타(E)는 스킵(`CompanyProfile.marketOrNull`).
- `DART_API_KEY` 미설정 → 수집 비활성(**데이터 공급 없음** — 스케줄러는 조용히 skip, 수동 트리거는 예외).
  쿼터 보호 호출간 150ms sleep, 일 2만콜.
- **개별 기업 실패는 집계만 하고 계속**(배치 전체 중단 금지). admin sync 는 202+백그라운드, 동시실행 409.
- 샘플 시드 마이그레이션(V2 코스피20+V3 코스닥10)은 **제거됨**(9d38e0ff5) — 기존 DB 는 Flyway
  `ignore-migration-patterns: "*:missing"` 관대 처리, 신규 배포는 DART 자동 수집으로만 채운다.

## 보안·경계

- 자체 최소 SecurityConfig(shared-common **미의존**, 스캔 `github.lms.lemuel.financial` 한정).
- `GET /api/financial/**` permitAll(공시 공개데이터 무인증), `/admin/financial/**` 은 `X-Internal-Api-Key`(AdminApiKeyFilter,
  운영 `internal-key-required=true` fail-closed), 나머지 denyAll.
- 코드·DB·이벤트 의존 0 — 소비측이 공개 GET 을 HTTP pull(loan/investment `adapter/out/external`). 이 서비스는 push 안 함.

## 안티패턴 (발견 시 지적)

- **PER/PBR·시가총액·주가·밸류에이션을 이 서비스에 추가** — 설계상 부재가 정상. 주가·시총은 market, 밸류에이션 조인은 소비측 몫.
- 비율을 DB 컬럼으로 물질화 / 계산불가를 예외로 변경(소비측 계약 파괴) / 자본잠식 가드 제거.
- 타 서비스 import·DB조인·Kafka 발행/구독 추가(경계 0 위반).
- GET 에 인증 요구(공시 무인증 원칙) / DART 실패를 배치 전체 중단으로 처리.
- shared-common(JWT·Outbox·Kafka) 끌어오기 / 루트 패키지 스캔.
