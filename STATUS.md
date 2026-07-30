# STATUS — Lemuel (Settlement)

> 이커머스 주문·결제·정산·선정산/기업대출·투자·계정계 + 공개조회 위성(재무제표·경제지표·기업뉴스·시세·공공데이터)·운영관제·AI챗봇 MSA 플랫폼 (Spring Boot 4.0 / Java 25 / 헥사고날)

**Last updated:** 2026-07-30

## 현재 상태
- **활성 브랜치:** `develop` (`main` 은 보호 브랜치 — PR 필수·squash 만·필수 CI 2종)
- **구성:** **13 마이크로서비스** + API Gateway + `shared-common` 공유 라이브러리(버전드 1.0.0)
  - 거래/금융: order(8088) · settlement(8082) · loan(8084) · investment(8100) · account(8102)
  - 공개조회 위성: financial(8086) · economics(8087) · company(8090) · market(8094) · commondata(8098)
  - 부가: operation(8092) · ai(8096) · organization(8104, 셀러/기업 조직·멤버십)
- **DB:** 13 서비스 모두 물리 분리(DB-per-service) — opslab / settlement_db / lemuel_{loan,financial,economics,company,operation,market,ai,commondata,investment,account,organization}
- **최근 커밋:** `59a7c5227` feat(account): ADR 0030 Phase 3 — 실체화 잔액 대사·정기 배치·드리프트 게이지

## 최근 진척 (2026-06-24 이후)
- **loan 담보/개인신용 대출 Phase 1** — 주택담보(`/loans/secured/mortgage`)·개인신용(`/personal`) 2종 추가. `Borrower`(개인·법인 공통 차주 VO) + `Collateral`(평가액 스냅샷, 설정→유효→말소) + `SecuredLoan`(담보 optional) 신규 애그리거트로 분리 — 기존 `LoanAdvance`·`CorporateLoan` 무수정(상장사 종목코드에 차주가 묶여 개인 표현 불가). 장기 분할상환 상품이라 연체·기한이익상실을 상태머신에 처음부터 포함. 원장은 기존 6계정만 사용하되 회차성 전표(SEC_REPAYMENT·SEC_INTEREST)를 중복분개 유니크에서 제외(미조치 시 2회차 상환부터 실패). `secured_loan_disbursed`/`.repaid` 발행(소비처는 Phase 2).
- **loan 담보대출 Phase 2 (P2-1~P2-7a, 2026-07-30)** — 담보유형 5종(부동산+보증·예금·채권·주식, Category 계열 행위)·담보권 순위(선순위 차감·0 clamp)·원장 계정 3종+실행 전표 7종·재평가 append-only 이력+마진콜(부분 유니크로 중복 차단)·담보 실행(처분/대위변제 경로별 손실 계정 분리)+상각(WRITTEN_OFF)·중도상환(실행시각 스냅샷 기산 수수료, 잔존비례·3년 면제, `/prepay`+Idempotency-Key 멱등)·기준금리 economics 실연동(BASE_RATE latest, 실패 시 설정값 폴백). gl-ledger-auditor 감사로 순차 재제출 멱등 공백(치명) 봉합. 수수료 taper 분모는 부과기간 1095일로 확정(2026-07-30). **잔여 이월 전량 소진(2026-07-30)** — 아래 GL 소비 매핑·담보평가 실연동(P2-7b) 항목 참조. Phase 2 전 항목 완료.
- **ADR 0030 Phase 3 — 실체화 잔액 대사 완료 (2026-07-30)** — `account_balances`(파생 캐시) vs 원장 재합산(정답지, Phase 1 백필과 동일 credit-positive 식)을 전 (owner, account) 쌍 FULL OUTER JOIN **단일 문장 스냅샷**으로 대조(read_committed 자기모순 방지·원장 스캔 1회 — 감사 MED-3). `TrialBalanceQuery.balanceRecon()` + `/control-recon` 응답 확장(`materializedRecon`) + **`healthy()` 종합 판정**(원장 폐루프 ∧ 캐시 정합 — balanced 단독 판정의 캐시 오염 사각 봉합, 감사 MED-2). 정기 배치 `BalanceReconScheduler`(기본 10분, `app.recon.balance.*`) + 게이지 3종 `account.balance.recon.{drift.count(−1=미검증), checked.pairs, last.success.epoch}` — 실행 실패는 게이지 불변+epoch 정체로 드러난다(실패의 '정합 0' 위장 차단, 감사 MED-1). **오염 3유형(값 왜곡·캐시 행 유실·고아 캐시 행)을 실 PG 주입으로 검출 실증**(`BalanceReconIT`). 자동 정정 없음 — 정정은 원인 규명 후 Phase 1 백필 재실행(운영 판단). gl-ledger-auditor 감사 HIGH 0·MED 3·LOW 4 중 MED 전건+LOW 2건 반영. Phase 2(전역 라우팅)는 여전히 HOLDBACK 재분류 계정 확정 대기.
- **loan 담보평가 실연동 P2-7b 완료 (2026-07-30)** — 금융자산담보 상품 `FINANCIAL_ASSET` 신설(`POST /loans/secured/financial-asset`, 예금·채권·주식 + 유형별 인정비율 한도, 담보형 고정 가산금리 재사용, 도메인이 금융자산 계열 담보만 허용). 담보평가 포트를 `ValuationClaim`(조회키·수량) 기반으로 확장하고 `SatelliteCollateralValuationAdapter` 로 실연동 — EQUITY=market 종가×수량, REAL_ESTATE=commondata 실거래가(recordKey 접두어·만원 단위, 수집 소스 미설정 시 비활성), **조달 실패·키 부재는 전부 제시값 폴백**(기준금리 P2-7a 와 동일 가용성 원칙, 어댑터 테스트 9건). 계약 productType enum 에 FINANCIAL_ASSET 추가(스키마 2종+프로듀서 테스트), product CHECK 2종 마이그레이션 + SchemaEnumContractIT enum↔CHECK 대조 추가. 게이트 loan 623·account 144(skip 0) GREEN.
- **loan 담보대출 account GL 소비 매핑 완료 (2026-07-30)** — `secured_loan_repaid` 에 `prepaymentFee` 옵셔널 필드 확장(중도상환 완제 실액·회차 완제 0, N5 string) 후 account-service 가 두 토픽을 소비: 신규 `OwnerType.BORROWER`(개인·법인 공통 차주 userId) + `GlAccount.SECURED_LOAN_RECEIVABLE`, 실행 DR 채권/CR 현금·완제 DR 현금/CR 채권 **원금만** 분개(둘 다 계약 원금이라 경로 무관 채권 0 마감, 이자·수수료는 loan 원장 소관 — 법인대출 선례 동형). 컨슈머 2종 멱등 2단 + CHECK 4종 확장 마이그레이션(`V20260730120000`, SchemaEnumContractIT 팩토리 20종 동기화). 게이트 loan 602·account 144·shared-common 전부 GREEN(**Docker 다운 가짜 GREEN 1회 적발 후 IT skip 0 재검증**), event-contract-reviewer MED 1·LOW 2 → 전건 수정.
- **위성·확장 서비스 9종 추가** — financial·economics·company(ADR 0023)·operation·market·ai·commondata·investment·account.
  공개조회 위성은 shared-common 미의존/제한 스캔 + 자체 최소 SecurityConfig(GET 공개, `/admin/**` 는 X-Internal-Api-Key 게이트).
- **금융 계정계 확장** — investment(CEO 투자하기: 투자점수·투자주문) + account(전사 복식부기 GL 집계, 소비 전용) + loan 기업대출(CorporateLoan) + CEO 프론트 메뉴.
- **이벤트 계약-as-code (ADR 0024)** — cross-service 24개 토픽 JSON Schema + 정본 샘플을 `shared-common/testFixtures` 에 단일 출처화, 프로듀서·컨슈머 양방향 계약 테스트로 드리프트 빌드 시점 차단.
- **organization-service 추가(13번째)** — 셀러/기업 조직·멤버십(OWNER/MANAGER/STAFF), 이벤트 발행 전용(`lemuel.organization.created`/`member_joined`, 소비처 미배선).
- **실데이터 자동수집 전환** — DART·KRX·ECOS 자동 수집 스케줄러 + 동기화 신선도 Prometheus 메트릭(선별 복구), 위성 샘플·데모 시드 제거(실데이터로만 적재).
- **loan 상환 시뮬레이션** — `POST /loans/repayment/simulate`: 원금·기간·이자율·상환방식(만기일시/원리금균등/원금균등)으로 회차별 상환표를 미리 계산하는 순수 미리보기(부수효과·영속화 없음).
- **company↔financial 마스터 통합 (ADR 0025)**, **기업 마스터 일괄등록** 엔드포인트.
- **PG 대사 승인 → 역정산(clawback) 루프 마감** — Discrepancy 승인 소비 핸들러 구현(과거 "다음 할 일" 완료).
- **문서 정비** — 기능명세 `SPEC.md` 추가, 12개 서비스 도메인 규칙 스킬(`*-rules`) + 커맨드 추가, `CLAUDE.md` 에이전트 지침 재구성(SPEC·스킬 위임).
- **k8s postgres 16→17 정합**.
- **정산 P0 피드백 사이클 착수 (2026-07-20~)** — 갭 감사 → 시드 6종(payout 배선·조정 원장·이벤트 격리·탐지 백필·E2E·payout 복구) 도출.
  실행분: 정산 확정·홀드백 해제 → Payout 자동 생성 배선(멱등), 차지백·PG 대사 조정 역분개 1:1 연동, PIT 뮤테이션 베이스라인 배선 + SURVIVED 16건 제거,
  과거분 멱등 백필(P0-4) — Payout 미생성·역분개 누락 `/admin/backfill/**` 정정(지급유형별·append-only), 2회 실행 2회차 0건 멱등 IT 증명.
- **ADR 0026 Option ① 완료** — payout 현금흐름 GL 폐루프(`2868fb9b2`). 결정(2026-07-23 Accepted) + 구현 병합 완료:
  계정 3종 추가(`HOLDBACK_PAYABLE`·`SELLER_RECOVERY_RECEIVABLE`·`WITHHOLDING_PAYABLE`), 감액 사건 GL mirror 컨슈머 9종,
  초과 실지급 분할 라우팅 + 셀러 advisory 락(`RecordPayoutService`), 시산표 실검증 `normalBalanceRespected()`(`balanced()` 는 방어값으로 강등).
- **ADR 0030 Phase 1 — 통제계정 잔액 실체화** — `account_balances`(owner·account 유일키) + 기존 전표 재합산 백필.
  전표 삽입이 실제 1행일 때만 양 레그 델타 UPSERT(중복 수신 시 잔액 불변 가드), `sellerPayableBalance` 를 O(1) PK 조회로 교체.
  부호 규약 credit-positive 로 기존 재합산식과 동일해 의미 보존, 재합산 쿼리는 Phase 3 대사의 정답지로 보존.
  `MaterializedBalanceIT` 로 "실체화 == 원장 재합산"·중복 수신 불변 실증.
- **투자 추천 휴장일 스킵 + 프론트엔드 lint 부채 0 (PR #174)** — 시세가 T+1 이라 "오늘이 휴장일인가"는 판정 불가.
  대신 시세 기준일 앵커(새 종가 도착 시에만 스크리닝, 실행 기록 `screening_runs` 로 판정)로 전환해 거래 없던 날짜 세트 생성을 차단.
  프론트는 ESLint 설정 부재(항상 실패) 해소 후 부채 95건 전부 정리(any 83·exhaustive-deps 10·react-refresh 2) →
  `--max-warnings 0` 강제 + `typecheck:tests` CI 게이트 추가(123건 오류 해소).
- **하네스 절차 규율층 자체 내재화** — debugging/tdd/verify 스킬 3종 + 라우터 주입(플러그인 독립), 하네스 런타임 `.omc`→`.claude/harness` 이전, 루트 문서 6종 docs/ 이관.
- **operation-service Phase 3 베이스라인 이상탐지** — 신규 `anomaly` BC: `ops_metric_bucket` 실패율 카운터 5종을 5분마다 롤링윈도우 z-score(최소표본·상대임계·정상복귀 게이트)로 판정 → `source=ANOMALY` 인시던트 자동 생성/refire/자동해제. 마이그레이션 0(기존 인시던트 라이프사이클 재사용), 테스트 16건+합성 백테스트, 로컬 실기동 검증 완료 (docs/design/operation-service-phase1.md §Phase 3).

## 진행 중
- P0 시드 3(이벤트 격리) — 병행 세션이 develop 위에 재구현 진행 중(`ConsumedEventQuarantine` 옵트인 훅). 시드 클레임 정본: `.symposium/scratch/seed-claims.md`
- account GL 통제계정 **음수 방지 전역화** — ADR 0030(Proposed, 결정 대기).
  **Phase 1(잔액 실체화, `2acff1417`)·Phase 3(대사, `59a7c5227`) 완료 — 남은 것은 결정 + Phase 2(라우팅 전역화)뿐.**
  회계 오너 확정 필요 3건 중 **HOLDBACK_PAYABLE 초과분 재분류 계정 미정이 Phase 2 블로커**
  (ADR 0026 후속, `74dfa486a` 가 명시 유보한 코드리뷰 #5·#6)
- operation-service 로드맵: Phase 3 베이스라인 이상탐지 **완료** → 다음은 Phase 4 AI 브리핑
- 커버리지 게이트 LINE 90% 상향 후속 — 신규 서비스 통합테스트 보강

## 다음 할 일

**다음 세션 추천 착수(사용자 결정 불필요·범위 명확 순):**
- [ ] **payout 파이프라인 실송금 트리거 + 셀러 계좌 레지스트리** (그린필드, 규모 M) — 생성 배선(정산 확정→Payout 멱등 생성)은
      완료, 실송금·계좌 레지스트리만 잔여. 계좌정보 암호화(`PAYOUT_ENC_KEY`)·PII → `security-auditor` 검수 권장
- [ ] operation-service **Phase 4 AI 브리핑** (Phase 3 베이스라인 이상탐지까지 완료, 로드맵 docs/design/operation-service-phase1.md)
- [ ] 커버리지 게이트 90% 후속 — 신규 서비스 통합테스트 보강

**사용자(회계 오너) 결정이 선행인 것:**
- [ ] **ADR 0030 결정 확정** → Phase 2(라우팅 전역화) 착수. Phase 1(실체화)·Phase 3(대사)는 완료(2026-07-30) —
      남은 결정: 음수=재분류 vs 금지 / 잔액 정본 / **HOLDBACK_PAYABLE 초과분 재분류 계정**(Phase 2 블로커)
- [ ] account GL 통제계정 음수 방지 **전역 불변식** (ADR 0030 §결함 1, Phase 2 본체) — `withholding_accrued`·`settlement_canceled`·
      `recovery.offset` 등 잔액 비의존 무조건 DR 이 통제계정을 음수로 몰 수 있음. 위 결정 확정 후 착수
- [ ] ADR 0026 열린 질문 ④ — 수동 payout(`settlementId=null`) 정책 확정 (MEDIUM, 현재는 `normalBalanceRespected` 가 사후 방어)

**외부 조건 대기:**
- [ ] commondata 실수집 검증 (`DATA_GO_KR_API_KEY` 확보 시) — 확보되면 loan 주택담보 실거래가 평가도
      `app.loan.commondata.real-estate-source` 설정으로 활성화(코드는 P2-7b 로 완료, 폴백은 제시값)
- [ ] ADR 0022(이벤트 스키마 레지스트리) 정식 도입 검토 — 현재 계약-as-code(0024)가 경량 선행 단계

**완료(2026-07-30 마감분):** `sellerPayableBalance` O(1) 교체(Phase 1) · ADR 0030 Phase 3 대사 ·
담보대출 account GL 소비 매핑 · 담보평가 실연동 P2-7b(담보대출 Phase 2 전량 소진) — 상세는 `## 최근 진척`

## 주요 위험/메모
- `DATA_GO_KR_API_KEY` 미보유로 common-data-service 실수집 경로 미검증(소스 등록→조회 전과정은 검증됨)
- 로컬 `bootRun` 은 cwd=모듈 디렉토리라 루트 `.env` 미로딩 → `--args="--JWT_SECRET=... --POSTGRES_*=..."` 주입 필요
- 외부 `main` 머지가 `develop` 으로 유입 → push 전 `git pull --rebase` 습관화
- `codex/settlement-p0-feedback` 브랜치는 **참조용 보존·회수 금지** (2026-07-22 처분 확정): 시드 3 은 develop 재구현이 대체,
  시드 6·조정분류·Task4/6 은 develop 재작성(역분개 일반화·typed payout)과 돈 경로 핵심 4파일 정면충돌 — 재구현 시 참조만
- 운영 배포 필수 주입: 강한 `JWT_SECRET`, `app.security.internal-key-required=true`, 각 서비스 외부 API 키

## 핵심 수치 (2026-07-30 기준 · git-tracked 소스)
> ⚠️ 수치는 `build/`·`.claude/worktrees/` 사본을 **제외한 git ls-files 기준**. 각 줄 끝 명령이 정답 —
> 드리프트 의심 시 명령을 돌려 재검증하고 이 수치를 갱신할 것(휘발성 수치를 명령 없이 손으로 적지 말 것).
- 서비스 **13개** + API Gateway + Kotlin polyglot 2(notification·reconciliation) — `git ls-files '*/src/main/resources/application.yml' | wc -l` → 16(=13+gateway+kotlin 2)
- Flyway 마이그레이션 **234개** — `git ls-files '*/src/main/resources/db/migration/*.sql' | wc -l` → 234
- ADR **29개** (0001~0030, 0019 결번 — 세무 ADR 은 0027 충돌로 0029 재부여) — `git ls-files 'docs/adr/[0-9]*.md' | wc -l` → 29
- 테스트 클래스 **718개** (Testcontainers 통합테스트 포함) — `git ls-files '*/src/test/*Test.java' '*/src/test/*Tests.java' '*/src/test/*IT.java' | wc -l` → 718
- 이벤트 계약 스키마 **24토픽** (ADR 0024, 프로듀서·컨슈머 양방향 테스트 — 담보대출 2종 포함) — `git ls-files 'shared-common/src/testFixtures/resources/contracts/events/*.schema.json' | wc -l` → 24

## 최근 전체 검증 (2026-07-29)
> ⚠️ 테스트 건수를 인용할 때는 **그 빌드에서 실제로 실행된 태스크만** 센다. `test` 가 UP-TO-DATE 면
> `build/test-results/` 의 XML 은 과거 실행분이라, 통째로 합산하면 낡은 수치가 섞인 가짜 GREEN 이 된다.
- `./gradlew build` **전체 통과**(15m01s, Docker UP) — 이번 실행에서 `test` 가 **실제 실행된 10개 모듈**
  합계 **3,534건**, failure 0 · error 0. skip 1건은 소스에 `@Disabled` 로 명시된
  `SettlementControllerTest > GET /settlements/{id}/pdf`(Boot 4 WebMvcTest binary response 이슈)이며,
  Testcontainers 통합테스트가 조용히 skip 된 건 0.
  (order 1153 · settlement 987 · loan 367 · company 242 · shared-common 225 · investment 212 ·
  account 134 · operation 92 · ai 85 · organization 37)
- UP-TO-DATE 로 재실행되지 않은 5개 모듈(**shared-common 미의존 공개 위성** — 이번 변경의 영향권 밖):
  financial 89 · economics 88 · commondata 87 · market 74 · gateway 2 = 340건, 최종 green 2026-07-24.
  → 저장소 전체 합계는 3,874건이다.
- 검증 중 발견·수정: `OutboxEvent.pending()` 이 `Instant.now()` 를 나노초째 담아 `timestamptz`
  (마이크로초 해상도) 왕복이 무손실이 아니었다(`OutboxClaimConcurrencyIT` N4 실패). 어서션을 완화하는
  대신 생성 시점에 `truncatedTo(MICROS)` 로 잘라 왕복을 무손실로 만들었다.
- 제출물 플러그인을 **호출 대상 서비스 기준**으로 재배치한 뒤 부트 jar 5종 실물 검사 —
  `pwc/`·`settlement-copilot/`·`fashion-copilot/`·공공데이터 CSV 엔트리 **0건**(`processResources` exclude 반영).
  company-service 리소스 산출물 111M → 84K.
- 하네스 3종: `node --test scripts/harness/test/*.test.mjs` **109/109** · `harness-audit` healthy ·
  `guard.mjs --staged` clean.

## 참고 문서
- `SPEC.md` — 전체 기능명세(엔드포인트·도메인 규칙·이벤트 카탈로그)
- `CLAUDE.md` — 에이전트 운용 가이드 / 아키텍처 경계·컨벤션
- `README.md` — 프로젝트 개요 · `STRUCTURE.md` — 모듈·디렉토리 구조 정본 · `PORTFOLIO.md` — 면접용 1장 요약 · `HARNESS.md` — 개발 하네스 구성
- `docs/adr/` — 아키텍처 결정 기록 (개수는 위 `핵심 수치`) · `*-rules` 스킬 — 서비스별 강제 도메인 규칙
- `docs/ouroboros.md` — Ouroboros(명세 우선 AI 워크플로 엔진) 아키텍처·스킬·핵심 개념 참조
