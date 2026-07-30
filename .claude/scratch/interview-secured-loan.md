# Interview 결과 — loan-service 담보대출 추가 (Path B, 2026-07-29/30)

> Ouroboros MCP 인터뷰가 codex 프로파일 미설정으로 행(hang) 걸려 Path B(플러그인 폴백)로 진행.
> MCP 세션에 저장되지 않았으므로 이 파일이 유일한 기록.

## goal (Restate 승인됨)

loan-service 에 개인·법인 공통 `Borrower` 와 선택적 `Collateral` 을 갖는 `SecuredLoan` 애그리거트를
신규로 추가해, **주택담보대출**(유효담보가치×LTV 한도)과 **개인신용대출**(외부 CB 점수 스냅샷 밴드)
2종을 신청→승인→실행→장기 분할상환→연체·기한이익상실→완제까지 처리하고, 담보 설정/해지 상태머신·
기존 6계정 복식부기 전표·신규 Outbox 토픽 2종(`secured_loan_disbursed`/`secured_loan_repaid`)을
남기되, 기존 `CorporateLoan`·`LoanAdvance` 와 account-service 는 일절 수정하지 않는다.

## constraints (사용자 확정)

- 구조: **신규 애그리거트만 추가(최소 침습)**. 기존 `CorporateLoan`(stockCode 기반)·`LoanAdvance` 무수정.
  Flyway 는 신규 테이블만 CREATE. 기존 테스트 무영향.
- 차주: 개인·기업 공통 범용 `Borrower` 모델 (INDIVIDUAL | CORPORATE).
- 담보: `Collateral` 은 optional (개인신용대출은 null).
  Phase 1 포함 = 평가액 + LTV 한도 산정, 담보 설정/해지 상태머신(설정→유효→말소).
- 감정가: `CollateralValuationPort` 를 정의하고 Phase 1 구현체는 **신청 시 입력값 스냅샷**을 반환.
  (Phase 2 에서 위성 서비스 어댑터로 교체 — 도메인 변경 0)
- 심사: 담보대출은 신용평가 생략(LTV 만). 개인신용대출은 **외부 CB 점수를 신청 시 입력·스냅샷** 후 밴드 매핑.
- 한도: 유효담보가치 × 담보유형별 LTV.
- 금리: 기준금리 + 등급별 가산금리. **Phase 1 은 기준금리도 포트/설정값**(economics-service 연동은 Phase 2).
- 상환: 기존 `RepaymentSchedule`(BULLET/원금균등/원리금균등) 재사용, 장기(360개월급) 분할상환표.
  연체·기한이익상실 **Phase 1 포함** — `LoanOverdueScheduler` 결합, 연체이자 전표 포함.
- 원장: **기존 `LedgerAccount` 6계정 그대로**, 계정과목 확장 없음. 차1·대1 구성적 균형 유지.
- 이벤트: Outbox 로 `lemuel.loan.secured_loan_disbursed` / `lemuel.loan.secured_loan_repaid` **발행만**.
  계약 JSON Schema + 정본 샘플을 shared-common testFixtures 에 등록. **소비자는 Phase 1 에 없음**.

## non-goals (Phase 2+ 로 명시 이월)

- 보증부 대출 · 금융자산 담보대출 (담보유형 2종)
- 담보권 순위(선·후순위) / 유효담보가치에서 선순위 차감
- 담보가치 재평가 · 마진콜
- 담보 실행(처분 · 대위변제)
- 중도상환 · 중도상환수수료
- 위성 서비스 실연동: market-service(시가) · common-data-service(실거래가) · economics-service(기준금리)
- account-service GL 소비 매핑 추가
- `LedgerAccount` 계정과목 확장
- 정산금(매출채권) 담보 상품 — 기존 `LoanAdvance` 영역, 별도로 둠

## acceptance criteria

1. `./gradlew :loan-service:test` + `:loan-service:jacocoTestCoverageVerification` (LINE 90%) 통과
2. 도메인 경계값 전수 테스트 — LTV 밴드, CB 점수→등급 경계, 상환표 마지막 회차 잔여흡수,
   상태전이 불가 경로 (`CorporateCreditPolicy` 선례와 동형)
3. 원장 차대 균형 불변식 테스트 — 신규 상품 전 전표가 차1·대1, 시산표 일치
4. 이벤트 양방향 계약 테스트 (ADR 0024) — 신규 토픽 2종 스키마·샘플 정합
5. 통합테스트 — 신청→승인→실행→분할상환→완제 E2E (실 DB)

## 인터뷰 중 확인된 코드 사실 (`[from-code]`)

- 기존 상품: `LoanAdvance`(선정산) · `CorporateLoan`(상장사 무담보 신용대출, `stockCode` 6자리 필수)
- `CorporateCreditPolicy`: 재무제표(안정성40+수익성40)+평판20 → 0~100 → A~E, 밴드 테이블 패턴
- `RepaymentSchedule`: BULLET/EQUAL_PRINCIPAL/EQUAL_PAYMENT, `RoundingPolicy` 주입, 마지막 회차 잔여 흡수
- `LedgerAccount` 6계정: LOAN_RECEIVABLE, CASH, FEE_RECEIVABLE, FEE_INCOME, BAD_DEBT_EXPENSE, BAD_DEBT_ALLOWANCE
- 현행 발행 토픽 3종: `lemuel.loan.disbursement_requested` / `corporate_loan_disbursed` / `repayment_applied`
- 조달 패턴 2종 기존재: Kafka 프로젝션(Settlement·CompanyReputation 컨슈머) / 직접 HTTP(`FinancialApiClient`)
- 담보(collateral) 개념은 loan 도메인에 전무 — 이번이 신규 축

## 환경 이슈 (다음 ooo 단계 전 해결 필요)

- `~/.ouroboros/config.yaml` → `orchestrator.runtime_backend: codex`,
  `llm_profiles.*.providers.codex.profile = ouroboros-{fast,standard,deep,frontier}`
- `~/.codex/config.toml` 에 해당 `[profiles.ouroboros-*]` 가 **하나도 없음** → MCP 질문 생성기가 무한 대기
- `runtime_controls.mcp_tool_timeout_seconds: 0.0` (타임아웃 없음) 이라 실패해도 영원히 멈춤
- 플러그인 0.50.5 (마켓플레이스 랙), MCP 서버 패키지 `ouroboros-ai` 는 0.50.6
