# 정산 코어 구조 안전성 & 엣지 케이스 감사 리포트

- **대상**: `settlement-service` · `account-service`(GL) · `loan-service`
- **축**: ① 아키텍처 경계 ② 금융 불변식 ③ 이벤트/멱등
- **방법**: 실행 증거(게이트/테스트) + 정적 코드 논증. READ-ONLY(코드 수정 없음).
- **날짜**: 2026-07-24 · **출처**: Ouroboros interview(Path B) → Seed `../../../.claude/scratch/audit-seed.md`
- **검증 표기**: `✅검증` = 감사관(메인)이 직접 파일을 열어 재확인 / `▪보고` = 서브에이전트 근거 보고(파일:라인 포함)

---

## 0. 요약 (Executive Summary)

세 서비스의 **회계 핵심 불변식(복식부기 균형·POSTED 불변·역분개·멱등·상태머신)은 도메인 팩토리 + DB 제약 + guard.mjs 3중으로 강하게 강제**되며, 이 층위에서는 심각한 갭이 없다. 위험은 대부분 **"방지(prevention)가 아니라 탐지(detection)에 의존"** 하거나 **"광고된 게이트가 특정 경로에서 비대칭적으로 비어 있는"** 곳에 몰려 있다.

| 심각도 | 건수 | 헤드라인 |
|---|---|---|
| **HIGH** | 1 | 선정산 대출 실행(`/loans/{id}/disburse`)에 인가·소유권 검증 부재 — 무권한 강제 실행 + IDOR · **🛠️ 수정 완료(소유권 대조, 게이트 통과)** |
| **MED** | 5 | settlement 헥사고날 방향 ArchUnit 미강제 · MSA guard allowlist 누락 · 수동 payout 음수잔액 · 소비전용 가드 우회 경로 · loan 연체/상각 lifecycle dead code |
| **LOW** | 7 | 멱등 TOCTOU · 이벤트 순서역전 · scale>2 무경고 반올림 · 상태머신 직접대입 · Outbox 스키마 하드코딩 등 |

**한 줄 결론**: 돈이 "잘못 계산/이중기표"될 구조적 위험은 낮다(구성적 균형이 원천 차단). 대신 **① 무권한으로 자금이동을 *트리거*할 수 있는 인가 갭(HIGH) 하나**와, **② 회계 이상을 *막지* 못하고 *탐지만* 하는 잔여 리스크(MED/LOW)** 가 핵심 개선 지점이다.

---

## 1. 실행 증거 (Execution Evidence)

| 증거 | 결과 |
|---|---|
| `node scripts/harness/harness-audit.mjs` | ✅ `harness-audit: healthy` (문서 드리프트·라우팅·가드 무결성 정상) |
| Docker 환경 | ✅ `29.6.2` 가용 → Testcontainers 통합테스트 skip 없이 실행 가능 |
| ArchUnit(정적) 실측 | account=`AccountArchitectureTest`(방향 3규칙 ✅), loan=`LoanArchitectureTest`(방향 3규칙 ✅), **settlement=방향 규칙 없음**(프로젝션·opslab 문자열 검사 2종뿐) → MED-1 근거 |
| `:settlement:test :account:test :loan:test` + `jacocoTestCoverageVerification` | ✅ **BUILD SUCCESSFUL (12m 28s)** — 3모듈 test + jacoco 커버리지 검증(LINE 90% 게이트) 전부 통과 |
| 테스트 실측(JUnit XML 집계) | settlement **960**(skip 1) · account **110**(skip 0) · loan **296**(skip 0) = **총 1,366, 실패 0·에러 0** |
| Testcontainers 통합테스트 실행 여부 | ✅ 실행됨 — 로그에 loan Testcontainers Postgres 포트(`localhost:12348`) 접속 관측. Docker 가용 + skip 사실상 0 → **가짜 GREEN 아님** |

> **관측 노이즈(무해)**: gradle 로그 끝에 loan `OutboxPublisherScheduler.publishPendingEvents` 가 컨텍스트 종료(`ionShutdownHook`) 중 `Connection refused localhost:12348`(테어다운된 Testcontainers 포트)로 스택트레이스를 남김. **테스트 실패 아님**(BUILD SUCCESSFUL) — Outbox 폴러(2s 주기)가 테스트 컨텍스트 종료와 경합해 마지막 tick 이 사라진 DB 를 친 것. L-1/L-6(스케줄러·폴러 운영 노이즈) 성격과 동류의 실관측 증거.
> settlement 의 skip 1건은 단일 조건부/@Disabled 로 추정(전수 대비 0.1%), Docker 부재로 인한 통합 대량 skip 아님.

---

## 2. 갭 목록 (심각도 정렬)

### 🔴 HIGH-1 · 선정산 대출 실행에 인가·소유권 검증 부재 (loan) — ✅검증 · **🛠️ 수정 완료**

- **위치**: `loan-service/.../adapter/in/web/LoanController.java:56-59` + `shared-common/.../jwt/SecurityConfig.java:179-200`
- **재현 시나리오**: 임의의 인증 USER 가 `POST /loans/{id}/disburse` 에 타 셀러의 대출 id 를 넣어 호출 → 소유권 확인도 역할 게이트도 없이 `disburse()` 실행 → `APPROVED→DISBURSED` 전이 + 원장 전표 + `LoanDisbursementRequested` 발행 → settlement 가 해당 대출의 셀러에게 실제 payout.
- **근거(직접 확인)**:
  - `LoanController.disburse(@PathVariable Long id)` — `Authentication` 파라미터 없음, 소유권/역할 검증 전무. (같은 컨트롤러의 `request`·`bySeller` 는 JWT 주체 파생·대조하는 것과 대조적, `:47-68`)
  - `SecurityConfig.java:179` — `/loans/corporate/*/disburse` 는 `hasRole("ADMIN")` 로 명시 게이트(주석: "실행(실자금 지급)… ADMIN 만"). 그러나 선정산 `/loans/*/disburse` 매처는 **부재** → `:200 anyRequest().authenticated()` 로 아무 인증 사용자 허용.
- **왜 위험**: 동종의 자금이동 액션인데 **기업대출=ADMIN 전용 / 선정산=무제한 인증** 비대칭. id 열거로 타인 대출을 강제 실행(운영 승인 게이트 우회)하고 실자금 이동을 트리거할 수 있다. (자금은 대출의 sellerId 로 가므로 "공격자 계좌로 탈취"는 아님 → 그래서 Critical 이 아닌 HIGH. 하지만 IDOR 는 프로젝트 핵심 가드레일이고 disburse 는 실자금 액션이라 HIGH.)
- **🛠️ 수정 완료 (2026-07-24)**: 코드 조사 결과 선정산 disburse 는 **셀러 셀프서비스**(프론트 LoanPage, `request` 도 JWT 파생)로 판명. 따라서 초기 권고였던 *SecurityConfig ADMIN 게이트는 오판*(적용 시 셀러 셀프서비스가 깨짐) → **컨트롤러 소유권 대조**로 수정.
  - `LoanController.disburse(@PathVariable Long id, Authentication)` — `callerSellerId(auth)`(미인증 403) → `loadLoanPort.load(id)`(없으면 400) → `loan.getSellerId() != caller` 면 `AccessDeniedException`(403). 기존 `requireSelf` 패턴과 동일, 애플리케이션 계층 순수 유지.
  - **TDD RED→GREEN**: 신규 인가 테스트 3종(타셀러 403·미인증 403·없는 대출 400, 모두 use case 미호출) 실패 목격 후 구현. 게이트 통과: `:loan-service:test :loan-service:jacocoTestCoverageVerification` **BUILD SUCCESSFUL**, loan-service **298 테스트 · 실패 0 · skip 0**.
  - **잔여(후속 판단거리, HIGH 아님)**: ① 운영(ADMIN/MANAGER)의 대리 실행은 현재 불가(owner-only) — 필요 시 오퍼레이터 override 추가 결정. ② 없는 대출 400 vs 타인 대출 403 의 존재 여부 열거 누수(LOW) — 강한 IDOR 비노출을 원하면 양쪽 통일.

### 🟠 MED-1 · ArchUnit 헥사고날 가드가 settlement·loan 에서 **버전 문제로 공허(무력화)** — ✅검증 · **🛠️ 부분 수정**

> 초기 표현("settlement 에 방향 테스트 없음")보다 **실체가 더 컸다**. 조사 중 진짜 근인을 발견.

- **근인(핵심)**: settlement·loan 이 `archunit-junit5:**1.3.0**` 사용 → **1.3.0 은 Java 25 바이트코드를 파싱 못 함** → ArchUnit 이 클래스를 조용히 스킵(`WARN Couldn't import class ...`) → `allowEmptyShould(true)` 규칙이 **전부 공허하게 통과**. 즉 감사가 "loan 방향규칙 ✅"로 크레딧했던 `LoanArchitectureTest` 도 **실제로는 no-op** 였다. account 만 `1.4.1` 이라 유일하게 실효.
- **재현(teeth 증명)**: settlement 를 1.4.1 로 올린 뒤 도메인에 adapter 필드 참조를 주입 → 도메인 방향 규칙이 즉시 FAIL(1.3.0 에선 통과). 버전이 가드 실효 여부를 갈랐음을 실증.
- **🛠️ 수정 완료분**:
  - `../../../settlement-service/build.gradle.kts`·`../../../loan-service/build.gradle.kts` ArchUnit `1.3.0 → 1.4.1`(account 정렬).
  - 신규 `SettlementHexagonalArchitectureTest`(domain→app/adapter 방향 + settlement→order/payment 코드경계) 추가 — 실효 확인.
  - 게이트 회귀 0: settlement **962**(skip1·fail0)·loan **298**(fail0), 기존 `SettlementProjectionArchitectureTest`·`LoanArchitectureTest` 도 1.4.1 에서 실효·통과.
- **🔎 상향 중 노출된 신규 위반 → 🛠️ 해소 완료**: settlement 를 실효화하자 **application→adapter 위반 20건**이 드러남 — out-port 3종(`SettlementReconciliationQueryPort`·`SettlementSearchQueryPort`·`SettlementSummaryQueryPort`)이 `adapter.out.persistence.querydsl.dto` 의 DTO 7종을 반환 타입으로 참조(역의존, 1.3.0 공허 가드에 가려져 있던 실채무).
  - **해소(리팩터)**: DTO 7종(`ApprovalStatusDto`·`PaymentRefundAggregationDto`·`SettlementCursorPageResponse`·`SettlementDetailDto`·`SettlementReconciliationDto`·`SettlementSearchCondition`·`SettlementSummaryDto`)을 `adapter.out.persistence.querydsl.dto` → **`application.port.out.dto`** 로 이전(git mv 7 + 참조 FQN 갱신 8 main + 2 test = 17파일, 순수 패키지 이동·동작/직렬화 무변경).
  - **결과**: `application 은 adapter 에 의존하지 않는다` 규칙 **실효 활성화**(보류 해제) → **account-service 완전 대칭**(1.4.1 + 3규칙 강제). 컴파일 통과, ArchUnit 3규칙 GREEN, DTO영향 테스트(QueryController·QueryRepositoryIT·Search·QueryService) GREEN.
  - **검증 비고**: 전체 settlement 게이트(970 테스트)에서 `PaymentRefundedAdjustWiringIntegrationTest` 컨텍스트 로드가 간헐 실패(MapStruct `SettlementPersistenceMapper` 빈 미해결)했으나 **격리 실행 시 통과** + 리팩터가 해당 매퍼를 건드리지 않음 → **환경 플레이크로 판정**(이 박스 세션 내 output.bin 락·워커 exit-1 크래시와 동류). full-gate 재실행으로 클린 그린 재확인.

### 🟠 MED-2 · MSA-BOUNDARY guard 의 import allowlist 에 `payment` 등 누락 (settlement) — ▪보고 · **🛠️ 수정 완료**

- **위치**: `../../../scripts/harness/guard.mjs` (구 정규식 커버 = `order|user|cart|product|coupon|shipping`)
- **재현**: `import github.lms.lemuel.payment.…`(payment 는 order-service 소속 도메인) 또는 `review/game/category` 를 settlement 코드에 추가해도 guard 통과. ArchUnit 방향 규칙 부재(MED-1) 라 3중 어디도 못 잡음.
- **왜 위험**: 실제 컴파일은 `project(":order-service")` 부재로 실패하겠지만, **계약상 방어선(실시간·pre-commit·CI guard)엔 구멍**. cross-service import 차단의 단일 출처가 불완전.
- **🛠️ 수정 완료 (2026-07-24)**: enum denylist(누락 취약)를 **inverse-allowlist**로 전환 — settlement 가 import 가능한 자기 컨텍스트 12종(`SETTLEMENT_OWN_PACKAGES` = settlement·payout·ledger·chargeback·pgreconciliation·recon·recovery·report·tax·idempotency·integrity·common)의 **여집합**(order 전 도메인 = payment·review·game·category·menu·rbac·commoncode·… 신규 포함)을 차단. denylist 나열 함정 제거.
  - **검증**: 현재 settlement 실코드 438파일 스캔 **false-positive 0** · 합성테스트로 `import github.lms.lemuel.payment.*` 차단·`ledger`/`common` 허용 확인 · `guard.test.mjs` **51 pass**(MED-2 회귀테스트 신규 잠금: order 8종 차단 + own 12종 허용) · `harness-audit: healthy`.
  - **비고**: settlement 자체 `github.lms.lemuel.recon`(OrderReconClient, HTTP 대사)은 허용 유지 — 코드 import 아닌 내부 API 호출이라 경계 위반 아님.

### 🟠 MED-3 · 수동 송금 payout 이 SELLER_PAYABLE 을 음수로 몰 수 있음 (account) — ▪보고 · **🛠️ 수정 완료 (채권 라우팅 + 동시성 락)**

> **🛠️ 수정 (2026-07-24, 정책: 채권 라우팅)**: payout 차변을 현재 SELLER_PAYABLE 잔액 기준으로 분할해 음수를 원천 차단.
> - `payable = min(amount, max(0, balance))` → `AccountEntry.payoutCompleted`(DR SELLER_PAYABLE/CR CASH), `advance = amount−payable` → **신규** `AccountEntry.payoutAdvanceReceivable`(DR SELLER_RECOVERY_RECEIVABLE/CR CASH, refType `PAYOUT_ADVANCE`). 둘 다 CR CASH·합=amount → 음수 없이 CASH 유출 정확 기록. 기존 회수채권 계정 재사용.
> - 신규 `RecordPayoutUseCase`/`RecordPayoutService`(분할), `LoadAccountEntryPort.sellerPayableBalance`(DB SUM), 컨슈머가 use case 호출로 교체, ref_type CHECK 확장 마이그레이션(`V20260724110000`, 17→18) + `SchemaEnumContractIT` 갱신.
> - **독립 리뷰(gl-ledger-auditor) → 동시성 HIGH 발견 → 하드닝**: 무락 read-then-write 라 같은 셀러 동시 payout(payoutId 파티셔닝+concurrency 3) 시 음수 재현 → **셀러 단위 `pg_advisory_xact_lock`**(2-키 네임스페이스)을 잔액 읽기 전 트랜잭션 내 획득해 직렬화. + LOW-1(음수/0/null amount 명시 거부→DLT) 수정.
> - **검증**: account **126 테스트 · 실패 0 · skip 0**, jacoco(LINE 90%) 통과. 신규 `PayoutConcurrencyIT`(2스레드 동시 payout → SELLER_PAYABLE 순잔액 0·회수채권 100·이중전기 없음, Testcontainers 실행)가 "버그면 −100" 회귀 가드. 소비 전용·MED-4 ArchUnit 유지.
> - **잔여(대사 캐비엇, 버그 아님)**: PAYOUT_ADVANCE 회수채권은 settlement 서브원장에 원천이 없어 자동 상계(recoveryOffset) 안 됨 — 운영 회수 필요.

**[원 보고]**

- **위치**: `account-service/.../PayoutCompletedConsumer.java:26-32`, `AccountEntry.java:213-217`
- **재현**: `settlementId=null` 수동 payout(대응 크레딧 없음) 수신 → `DR SELLER_PAYABLE / CR CASH` 를 크레딧 선행 없이 전기 → 셀러 SELLER_PAYABLE 순잔액 음수 → "완전정산 통제계정 0" 불변식 위반.
- **왜 위험**: 허위 음수 미지급금 계상. `TrialBalance.normalBalanceRespected()`·`AccountSummary.fullySettled()` 가 **탐지 가드**로 존재하나 **방지가 아님** — 정책 확정 전까지 "코드 봉합 말고 알람으로 다뤄라"고 문서화된 열린 항목. 정책 미정 상태의 잔여 리스크.

### 🟠 MED-4 · 소비 전용(발행 금지) 가드에 우회 경로 존재 (account) — ▪보고

- **위치**: `guard.mjs:93`(정규식 `/\bkafkaTemplate\.\s*send\s*\(/`), `AccountArchitectureTest.java:70-78`(포트 2종만 금지)
- **재현**: 원시 `KafkaTemplate` 을 다른 식별자(`producer.send(...)`)·`StreamBridge`·`ProducerFactory` 로 발행하면 텍스트 매칭이 놓치고, ArchUnit 은 `SaveOutboxEventPort`/`PublishExternalEventPort` 두 포트만 금지(KafkaTemplate 은 DLT 용으로 명시 제외).
- **왜 위험**: "account 는 발행 코드 0" 이 핵심 불변식인데 **식별자 이름 텍스트 매칭 의존** → 리네이밍/다른 발행 API 로 3중 가드 우회 가능.
- **🛠️ 수정 완료 (ArchUnit 정공법, 2026-07-24)**: guard-grep 은 이 건에 부적합했다 — 후보 벡터(`ProducerFactory`·`KafkaTemplate`)를 guard 에 넣으면 account 가 **DLT 발행용으로 합법 사용**하는 `KafkaErrorHandlerConfig`(`dltProducerFactory`/`dltKafkaTemplate`/`DeadLetterPublishingRecoverer`)에 **false-positive → account 커밋 차단**. 텍스트 매칭으로는 "비즈니스 발행"과 "DLT 발행"을 구분 불가.
  - 대신 **구조적 강제**: `AccountArchitectureTest`(1.4.1, 실효)에 5번째 규칙 추가 — *"account 코드는 `KafkaTemplate.send(..)` 를 **직접 호출**하지 않는다"*(타입 기반). 변수명을 `kafkaTemplate` 이 아닌 것(`producer.send(...)`)으로 바꾼 grep 우회를 **타입으로 포착**. DLT 는 `DeadLetterPublishingRecoverer`(프레임워크)가 send 를 내부 호출 → account 코드엔 직접 호출이 없어 **정상 DLT 배선은 false-positive 없이 통과**.
  - **검증**: 현재 account 코드에 직접 `.send(` 0건 → 규칙 통과 · **teeth-probe**(리네이밍된 `renamedProducer.send(...)`)로 규칙이 잡아 FAIL 확인 후 제거 · account **113 테스트 · 실패 0**, `jacocoTestCoverageVerification` 통과.
  - **잔여(범위 밖)**: `StreamBridge`/원시 `Producer.send` 벡터는 account 클래스패스에 부재라 미포함(필요 시 동일 패턴으로 확장 가능).

### 🟠 MED-5 · loan OVERDUE/WRITTEN_OFF 연체·상각 lifecycle 이 dead code (loan) — ▪보고 · **🛠️ 핵심 수정 완료**

> **🛠️ 수정 (2026-07-24)**: 연체·상각 lifecycle 의 **앱 계층 진입점을 신설**해 dead code 를 해소.
> - `ManageLoanCollectionUseCase`(포트) + `LoanCollectionService`(구현): `markOverdue`→도메인 전이+저장+메트릭, `writeOff`→도메인 전이+**대손 전표**(`LoanLedgerEntry.badDebtWriteOff`: DR BAD_DEBT_EXPENSE/CR BAD_DEBT_ALLOWANCE)+메트릭.
> - `LoanController` `/loans/{id}/overdue`·`/write-off` — 회수 운영 액션이라 **ADMIN 전용**(`requireAdmin`, 403 가드).
> - 검증: loan **319 테스트 · 실패 0 · LINE 96.7%**(게이트 통과). 도메인 테스트 `연체된_대출도_상환되면_REPAID로_회수된다`·`연체된_대출의_부분상환은_OVERDUE_유지` 등 8종 + 서비스 3 + 컨트롤러 4 + 원장 1.
> - **🛠️ saga OVERDUE 자동 recovery — 해소 완료 (정책: 자동 포함)**: 상환 saga `ApplyRepaymentService` 를 `findRepayableBySellerForUpdate`(DISBURSED**·OVERDUE**)로 확장 — 쿼리 `status in :statuses`, 포트/어댑터 rename. 연체 대출도 새 정산금으로 FIFO 자동 차감돼 OVERDUE→REPAID 복귀. `ApplyRepaymentServiceTest`(연체 회수)·`LoanPersistenceAdaptersTest`(DISBURSED+OVERDUE 조회) 추가.
> - **🛠️ 시간 트리거 자동화 — 완료 (병행 세션)**: `dueAt` 컬럼 마이그레이션 + `LoanOverdueScheduler`(만기 경과 자동 OVERDUE/상각 배치) 신설.
> - **병행 대조·검증**: 이 saga 변경은 병행 세션의 대규모 MED-5(수동 lifecycle·시간트리거·기업대출 상환)와 **같은 모듈에서 얽혔으나 중복 없이 상보적**. 병행 정착 후 재대조: 생성자 arity(10-arg dueAt) 정합, **loan 전체 게이트 331 테스트 · 실패 0 · jacoco 통과** 확인.


- **위치**: `LoanAdvance.java:99,114`(`markOverdue()`/`writeOff()` 정의), 앱 계층 호출자 0
- **재현**: 실행된 대출이 상환되지 않아도 어떤 배치/스케줄러/서비스도 `markOverdue()`·`writeOff()` 를 호출하지 않음(도메인 유닛테스트만 존재). 상환 saga 는 `findDisbursedBySellerForUpdate`(status=DISBURSED 한정)라 OVERDUE 로 넘어가도 자동 차감 안 됨.
- **왜 위험**: 연체 대출이 회계상 영원히 DISBURSED 로 남아 대손(BAD_DEBT)이 원장에 미반영. 상태머신 표(`LoanStatus.java`)가 광고하는 연체·상각 경로가 런타임에 부재 — 문서-코드 드리프트.

### 🟡 LOW (요약)

| # | 서비스 | 갭 | 근거 | 성격 |
|---|---|---|---|---|
| L-1 | account | append() 멱등 선점 TOCTOU — 동시 중복수신 시 첫 시도가 `DataIntegrityViolationException` | `AccountEntryPersistenceAdapter` | **✅ `INSERT ... ON CONFLICT DO NOTHING` 원자 upsert(예외·tx오염 없음)+실PG IT** |
| L-2 | account | 이벤트 순서 역전 방지 없음 — 크레딧보다 차변 선도착 시 일시 음수 | 무상태 append 컨슈머 | **✅ MED-3 채권 라우팅이 payout 선도착 케이스 커버 확인+문서화(순서강제 미추가—의도)** |
| L-3 | account | scale>2 금액 유입 시 조용한 HALF_UP 반올림 → 서브원장과 1원 드리프트 | `AccountEntry.of` | **✅ `of()` 초크포인트에서 scale>2 거부(타입예외+비재시도 DLT), trailing-zero 허용** |
| L-4 | settlement | `adjustForRefund`/`clawback` 가 상태머신 전이표 우회해 `status=CANCELED` 직접 대입 | `Settlement.java` | **✅ 두 지점을 `cancel()`(전이표 단일출처)로 라우팅, `canTransitionTo` 가드로 멱등 보존(순수 리팩터)** |
| L-5 | settlement | 정산 생성 멱등이 check-then-act(findByPaymentId→save), 동시 중복은 DB 제약 예외 의존 | `CreateSettlementFromPaymentService` | **✅ `save`의 `DataIntegrityViolationException` catch→재조회 멱등 수렴(기존 자매 LOW-fix 패턴). 캐비엇: 완전 무오염은 REQUIRES_NEW/ON CONFLICT 대공사, 최악=오늘 retro로 degrade** |
| L-6 | loan | Outbox 네이티브쿼리 `opslab` 스키마 하드코딩 결합 — 스키마 표준화 시 발행 정지 트랩 | `application.yml`, `PartitionMaintenanceRunner` | **✅ 재평가: 이미 해소됨(shared-common `OutboxSchema`·Runner 모두 default_schema 주입, 하드코딩 아님) → 스테일 주석만 정정** |
| L-7 | loan | 선정산 CreditPolicy 한도·수수료 미라운딩 `compareTo` 비교 — 경계 결정성 비일관 | `CreditPolicy.java:45-65` | **✅ 한도·수수료 `setScale(2,HALF_UP)` 정규화(기업정책과 일관)+경계 테스트** |

---

## 3. 커버리지 표 (축 × 서비스 — 강제 수단)

강제 수단: **G**=guard.mjs / **A**=ArchUnit / **D**=도메인·DB 제약 / **T**=테스트 / **✗**=미강제(관례뿐)

| 불변식 | settlement | account | loan |
|---|---|---|---|
| 헥사고날 domain→adapter 방향 | **✗** (MED-1) | A | A |
| MSA cross-service import 0 | G(부분,MED-2)+T | A | A |
| 소비 전용/발행 경계 | — | A+G(우회 MED-4) | Outbox 발행 정상 |
| 금액 BigDecimal / double 금지 | G+D | G+D | D(Money VO) |
| 복식부기 차1·대1 균형 | D(factory)+T | D(factory)+T | D+DB CHECK |
| POSTED 불변 / 역분개 | G+D+T | **DB 트리거**+G | D |
| 상태머신 전이 강제 | D+T (직접대입 L-4) | — | D+T |
| 수수료율/한도 스냅샷·경계 | D(final)+T | — | D+T(밴드 전수) |
| 홀드백/초과환불 차단 | D+T | — | — |
| 3단 멱등 | DB제약+컨슈머 | 2단+DB UNIQUE(L-1) | 3단+DB UNIQUE |
| Outbox 원자성(동일 tx) | 서비스 | (소비전용) | 서비스(스키마결합 L-6) |
| 계약 드리프트(양방향) | T | T(17 매핑↔CHECK) | T |
| 동시성 lost-update | PG락+IT | (순차만, L-1) | PG락+IT |
| 연체·상각 lifecycle | — | — | **✗ dead code** (MED-5) |
| IDOR/인가 | 소유권 대조 | /api/account ADMIN·MANAGER | 선정산 disburse **✅ 소유권 대조(HIGH-1 수정)** + 컨트롤러 T |

---

## 4. 안전 실증 (근거로 확인된 강한 불변식)

1. **반쪽 전표 원천 차단** — settlement `LedgerEntry.balancedPairForSettlement`(payment=net+commission 검증), account `AccountEntry` 생성자 `LedgerInvariants.requireDistinctAccounts`+`requirePositiveAmount`, loan `LoanLedgerEntry`+DB `chk_*_accounts_distinct`. 차=대·0·음수·null 전표는 **도메인에서 생성 불가**(타입 예외). 세 서비스 동형 팩토리.
2. **POSTED 불변 + 역분개 append-only** — account 는 **DB 트리거**(`enforce_account_entry_append_only`)가 UPDATE/DELETE 물리 차단 + guard IMMUTABLE-HISTORY 가 SQL·Java 양쪽 실시간 차단. settlement 역분개는 원본 미변경·신규 SALES_REFUND 전표 + `(refId,refType)` 멱등 skip.
3. **계약↔스키마 빌드시점 정합** — account `SchemaEnumContractIT` 가 팩토리 실호출 refType 집합 vs `pg_get_constraintdef` CHECK 정확 일치 대조(신규 매핑 시 CHECK 누락하면 빌드 실패). 세 서비스 양방향 계약 테스트(ADR 0024).
4. **동시성 직렬화** — settlement `findByPaymentIdForUpdate`(PESSIMISTIC_WRITE)+IT, payout `WHERE status=REQUESTED` 원자 선점+IT, loan `findByIdForUpdate`/FIFO 락. 이중지급·lost-update 방지 실증.
5. **상태머신 도메인 강제** — 허용 전이가 `*Status.canTransitionTo` 단일 출처, 애그리거트 `transitionTo`/`requireTransition` 위임 → 종료상태 재전이(이미 REPAID 재상환 등) 타입 예외 차단(L-4 의 2지점 직접대입만 예외).
6. **수수료율 정산시점 영구보존** — settlement `commissionRate` final + rehydrate write-once + setter 부재로 재할당 컴파일 불가.

---

## 5. 비고 · 한계

- **인용 정정**: loan 감사관이 인용한 `SecurityConfig.java:179` 는 loan-service 가 아니라 **shared-common** 소재(`shared-common/.../jwt/SecurityConfig.java`) — 라인은 정확, 모듈만 오귀속. 감사관(메인)이 직접 재확인해 HIGH-1 을 CONFIRMED 처리.
- **탐지 vs 방지**: MED-3/L-2 는 `normalBalanceRespected()` 조회시점 탐지에 의존하고 방지 계층이 없음. `TrialBalance.balanced()` 는 구성적 균형 탓에 항상 true(실검증력 없음)임을 코드가 자인 — 실 이상탐지는 `normalBalanceRespected()` 담당(설계 자각 양호).
- **비목표**: 본 리포트는 감사(발견)까지. 코드 수정·테스트 추가는 후속 과제. 범위 밖(order/위성/게이트웨이/폴리글랏) 제외.
- **실행 증거 완료**: §1 gradle test/jacoco 실측 반영됨(1,366 테스트 GREEN, 커버리지 게이트 통과). 정적 감사 + 실행 증거 결합 완료.
