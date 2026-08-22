# Seed — card-service 법인카드 as-is 사양 (한도·승인·매입·명세서·지출관리)

> **상태: CONFIRMED** (역산 결정화, 2026-08-22) · 정본 데이터: [`card-service-corporate-card.seed.yaml`](card-service-corporate-card.seed.yaml)
>
> ⚠️ **card-service 의 첫 as-is Seed 다.** 기존 [`card-service-funding-offset`](card-service-funding-offset.seed.md)
> 은 상태가 `DESIGN`(미구현 실행 스펙)이라 회귀 기준선이 아니다 — 그 Seed 는 이 문서가 기술하는
> 현행 위에 얹을 **to-be** 이고, 이 문서가 그 아래의 **as-is** 다.
>
> **as-is 원칙** — 결함은 교정하지 않고 Known Issues 로만 기록한다.

## Goal (한 줄)

**card-service(법인카드 — 카드계정·카드 한도 배분, 승인·매입, 청구 사이클, 지출증빙 대사)의 현행
동작을 실행 가능한 게이트에 매핑된 불변 사양으로 결정화해, 회귀 기준선 · 이벤트 계약 드리프트 방지 ·
funding-offset 스펙의 착지 기준으로 사용한다.**

## 범위

| 포함 | 제외 |
|------|------|
| 상태머신 6종(계정·카드·홀드·명세서·영수증·지출품의) | 재원 F 의 산출 자체(account-service GL 소관, ADR 0030) |
| 한도 산정식(F × R × H)과 마스터↔서브 불변식 | deposit hold/offset 연계(→ funding-offset Seed, 미구현) |
| 승인 4거절사유와 가맹점/MCC 정책 | 조직·멤버십 도메인(organization-service 소관 — 여기선 프로젝션만) |
| 청구 사이클(개시·적재·마감·납부·연체) | OCR 제공자 내부(Gemini 어댑터 구현 상세) |
| 매입↔영수증 대사 판정 규칙과 리뷰 큐 | |

## 핵심 불변식 (as-is, 파일:라인 근거)

> 경로 접두: `card-service/src/main/java/github/lms/lemuel/card/`

### 한도

1. **애그리거트의 핵심 책임이 `masterLimit >= Σ subLimit` 이다** (`domain/CardAccount.java:13-14`).
   발급·서브한도 변경은 `assertCanIssue` 를 통과해야 하고, 초과 시 `SubLimitExceededException`
   (`CardAccount.java:132-142`).

2. **한도 산정식** — `masterLimit = floor(F × R × H)` (`domain/CardLimitPolicy.java:10-15`):
   - `F = sellerPayable + holdbackPayable` (확정·미지급 정산금 + 홀드백 유보분)
   - `R` = 인정비율(설정 주입, 기본 0.70)
   - `H` = 평판 haircut — A·B 1.00 / C 0.85 / D 0.70 / **E 0.00** (`domain/ReputationGrade.java:11-15`)

3. **R 이 1 이 아닌 이유가 명문화돼 있다.** `F` 는 곧 셀러에게 지급될 돈이라 카드 이용과 정산 지급이
   **같은 재원을 두 번 쓸 수 있다**. 실제 상계는 청구 사이클의 몫이고 그때까지 `R` 이 그 위험을
   흡수한다 (`CardLimitPolicy.java:17-19`). ← funding-offset Seed 가 닫으려는 구멍이 바로 이것이다.

4. **하향은 Σ서브한도를 하한으로 클램프한다.** 이미 배분한 임직원 한도 아래로 마스터를 내리면
   **카드가 사전 통지 없이 무력화된다** — 재산정이 자동으로 도는 경로라 도메인에서 하한을 강제한다
   (`CardAccount.java:145-158`, `LimitChangeResult.clamped` 로 클램프 사실을 반환).

5. **재심사는 한도와 근거를 함께 갱신한다.** `changeMasterLimit` 만 쓰면 `limitSnapshot` 이 개설 시점
   근거로 남아 **서로 다른 심사를 가리키는 두 값**이 된다. 영속 계층이 `screened_at` 을 스냅샷 변화로
   판정하므로 그 어긋남은 **그대로 감사 기록의 거짓말**이 된다 (`CardAccount.java:160-183`).

6. **재심사는 ACTIVE 만 허용한다(SUSPENDED 제외).** 정지는 사람이 판단해 걸었거나 재심사가 강등한
   결과인데, 배치가 자동으로 한도를 다시 얹으면 **정지 사유가 해소됐는지 아무도 확인하지 않은 채
   여신이 되살아난다** — 복귀는 명시적 `resume()` 을 거쳐야 한다 (`CardAccount.java:174-178`).

7. **빌더 우회를 쓰지 않는다.** `Builder` 는 영속 계층 재구성 전용이라 상태 전이 가드를 통째로
   건너뛴다 — 그 길을 열면 **CLOSED·REJECTED 계정도 배치가 조용히 되살릴 수 있다**
   (`CardAccount.java:169-172`).

8. **ACTIVE·REJECTED 전이는 `LimitSnapshot` 이 필수다** (`CardAccount.java:63-67,86-92`).
   근거 없이 한도가 생기는 경로가 없다. 스냅샷은 **부호 있는 원본값을 그대로 보존**한다 —
   근거(시산표)로 한도를 재현해야 하기 때문이다 (`CardLimitPolicy.java:47-48`).

### 상태머신

9. **6종 모두 전이표가 enum 단일 출처**이고 표 밖 전이는 `InvalidCardTransitionException` 이다:

   | 애그리거트 | 전이 | 출처 |
   |---|---|---|
   | CardAccount | `SCREENING → ACTIVE\|REJECTED` · `ACTIVE ⇄ SUSPENDED\|DELINQUENT` · `→ CLOSED` | `CardAccountStatus.java:29-34` |
   | Card | `ISSUED ⇄ SUSPENDED` · `→ CANCELED` | `CardStatus.java:20-22` |
   | Hold | `ACTIVE → CAPTURED\|PARTIALLY_CAPTURED\|VOIDED\|EXPIRED` | `HoldStatus.java:22-30` |
   | Statement | `OPEN → CLOSED → PARTIALLY_PAID → PAID`, `CLOSED → DELINQUENT → PAID` | `StatementStatus.java:31-36` |
   | ExpenseReceipt | `EXTRACTED → MATCHED\|MISMATCHED\|NEEDS_REVIEW` | `ExpenseReceiptStatus.java:17-25` |
   | ExpenseReport | `DRAFT → SUBMITTED → APPROVED\|REJECTED` | `ExpenseReportStatus.java:20-26` |

10. **연체 후 전액 납부는 PAID 로 돌아온다** — `DELINQUENT → PAID` 가 전이표에 있다
    (`StatementStatus.java:35`). 명세서가 DELINQUENT 가 되면 연계 카드계정도 DELINQUENT 로 전이한다
    (`CardStatement.java:16-17`).

### 승인

11. **가용한도는 마스터·서브 두 축의 최소값이다**:
    `availableMaster = masterLimit − Σ(ACTIVE 홀드, 계정 단위)`,
    `availableSub = subLimit − Σ(ACTIVE 홀드, 카드 단위)`, `available = min(둘)`
    (`domain/AuthorizationHold.java:14-22`).

12. **`authorizationId` 가 자연키이자 멱등 키다.** 같은 값으로 승인이 재요청되면 기존 홀드를 그대로
    반환하고, DB UNIQUE 제약이 중복 저장의 **최후 차단선**이다 (`AuthorizationHold.java:10-13`).

13. **거절 사유는 정확히 4가지이며 추가 금지다.** 이 enum 은 `lemuel.card.authorized` 에 직렬화되므로
    **값 추가는 소비자 계약 파괴**다 — 신규 사유는 신규 토픽 버전으로만 (ADR 0022,
    `domain/DeclineReason.java:4-8`): `LIMIT_EXCEEDED` · `CARD_SUSPENDED` · `MEMBER_INACTIVE` ·
    `MERCHANT_POLICY_VIOLATION`.

14. **가맹점 정책 위반은 세부 사유를 밖으로 내보내지 않는다.** MCC 차단인지 한도 초과인지는
    **로그에만** 남긴다 — 거절 메시지를 세분화하면 정책을 역추적당한다
    (`domain/MerchantPolicy.java:12-15`). 평가 축은 차단 MCC · 허용 MCC(비면 전체 허용) ·
    1회/일/월 한도 · 해외 · 온라인 6종 (`MerchantPolicy.java:22-30,54-61`).

15. **임직원 이탈은 조직 프로젝션으로 판정한다** — `MEMBER_INACTIVE`. organization-service 의
    4토픽을 프로젝션으로 소비하며(`adapter/in/kafka/Organization*Consumer.java` 4종) card 는
    organization 을 코드·DB 로 의존하지 않는다.

### 청구 사이클 (도달성 확인 완료)

16. **매입이 명세서를 열고 채운다** — `lemuel.card.captured` 를 **두 컨슈머가 분담**한다:
    `CardCapturedStatementConsumer`(→ `OpenCardStatementUseCase`·`ChargeCardStatementUseCase`)와
    `CardCapturedExpenseConsumer`(→ 지출품의 생성). 한 토픽·두 관심사를 한 클래스에 넣지 않는다.

17. **명세서는 카드계정 × 청구주기(YearMonth)당 1개다** (`domain/CardStatement.java:15-16`).

18. **마감은 배치가, 납부는 내부 API 가 한다** — `CloseStatementUseCase` ← `StatementBillingScheduler`
    (매월 1일 01:00 KST), `PayStatementUseCase` ← `StatementPaymentInternalApiAdapter`.
    2026-08-13 역산 시점에는 **청구 사이클 입력이 통째로 없었고**(통합테스트가 명세서를 대신 생성했다)
    지금은 네 유스케이스 모두 인바운드 어댑터가 부른다(grep 실측).

19. **배치 4종의 시각이 겹치지 않는다** (Asia/Seoul):
    명세서 마감 매월 1일 `01:00` → 연체 판정 매일 `02:00` → 한도 재산정 매일 `03:30` →
    홀드 만료 매일 `04:00` (`adapter/in/schedule/*.java`).

### 지출증빙 대사

20. **판정 순서가 곧 정책이다** (`domain/ExpenseReceiptMatcher.java:12-25`):
    1. **신뢰도 미달 → `NEEDS_REVIEW`** — 믿을 수 없는 값으로 불일치를 선고하면 멀쩡한 영수증이
       종결(`MISMATCHED`)로 떨어진다. 사람 리뷰가 먼저다.
    2. **총액 → `compareTo` 정확 일치** — **1원 차이도 불일치**. 허용 오차를 두는 순간 그 오차만큼의
       증빙 없는 지출이 통과한다.
    3. **거래일 신뢰도 미달 → `NEEDS_REVIEW`** — 총액을 또렷하게 읽었다는 사실은 거래일에 대해
       아무것도 보장하지 않는다. 신뢰도가 필드 하나로 합쳐져 있던 시절, 비전 모델이 반사광에 덮인
       거래일을 **6년 틀리게 읽고도 0.98 을 붙여** 멀쩡한 영수증이 종결됐다.
    4. **거래일 → 매입일(KST) ±1일** — VAN 매입 시점과 전표 시점의 하루 차를 흡수한다.
       판독 불가(null)는 불일치가 아니라 **리뷰**다.

21. **상호명은 판정에 쓰지 않는다** — OCR 상호 표기("김밥천국 강남점")는 가맹점 등록명과 상시
    불일치한다 (`ExpenseReceiptMatcher.java:27`).

22. **`NEEDS_REVIEW` 는 사람이 종결한다** — `/admin/expense-receipts` 리뷰 큐(ADR 0036).
    무폴백 설계의 **사람 쪽 절반**이다.

## 이벤트 계약

**발행 8토픽**(카탈로그 owner = card-service, 순서키 전부 `cardAccountId`):
`card.account_opened` · `account_status_changed` · `issued` · `status_changed` · `limit_changed` ·
`authorized` · `captured` · `statement_paid`.

**소비 6토픽 / 컨슈머 7클래스** — `card.captured`(자기 발행을 되받아 명세서·지출 2클래스가 분담) ·
`organization.{created,member_joined,member_removed,member_role_changed}` · `company.reputation_changed`.

## 인터페이스

| 표면 | 경로 |
|---|---|
| 셀러/임직원 | `/api/cards` |
| 리뷰 큐(ADMIN) | `/admin/expense-receipts`, `/admin/expense-receipts/{receiptId}/review` |
| 내부 | `/internal/api/v1/cards`, `/internal/api/v1/statements` |
| VAN | `/van/v1/{authorizations,captures,refunds,voids}` |

## 수용 기준 (게이트 매핑)

| AC | 기준 | 게이트 |
|---|---|---|
| AC-1 | `masterLimit >= Σ 활성 서브한도` 가 어떤 경로에서도 깨지지 않는다 | `CardAccountTest` · `IssueCardServiceTest` · `ChangeSubLimitServiceTest` |
| AC-2 | 마스터 하향이 Σ서브한도로 클램프되고 그 사실이 반환된다 | `CardAccountTest` — `LimitChangeResult.clamped` |
| AC-3 | 재심사가 한도와 `LimitSnapshot` 을 함께 갱신한다 | `CardAccountTest` · `RecalculateCardLimitsServiceTest` · `LimitSnapshotTest` |
| AC-4 | 한도 산정식 `floor(F × R × H)` 와 등급 haircut 이 고정된다 | `CardLimitPolicyTest` · `ReputationGradeTest` · `ScreeningResultTest` |
| AC-5 | 승인 경로가 내부 API·VAN 양쪽에서 같은 결론을 낸다 | `EntryPointParityTest` · `AuthorizationInternalApiAdapterTest` · `AuthorizationVanAdapterTest` |
| AC-6 | 거절 사유 4종이 이벤트 계약과 일치한다(값 추가 = 계약 파괴) | `CardEventContractTest` · `EventContractConsumerTest` |
| AC-7 | 상태 전이표 밖 전이가 거부된다 | `CardAccountTest` · `CardTest` · `CardStatementTest` · `ExpenseReceiptStatusTest` |
| AC-8 | 가맹점/MCC 정책 6축이 강제된다 | `MerchantPolicyTest` |
| AC-9 | 총액 1원 차이가 `MATCHED` 로 통과하지 않는다 | `ExpenseReceiptMatcherTest` |
| AC-10 | 신뢰도 미달·거래일 판독 불가가 `MISMATCHED` 가 아니라 `NEEDS_REVIEW` 다 | `ExpenseReceiptMatcherTest` · `ExtractedReceiptTest` |
| AC-11 | 증빙 없는 지출품의가 승인으로 넘어가지 않는다 | `ExpenseWorkflowReceiptGateTest` · `ExpenseWorkflowDecouplingTest` |
| AC-12 | **모든 인바운드 포트가 어댑터에서 도달 가능하다** | `InboundPortReachabilityTest` (ArchUnit, 전이적 판정) |
| AC-13 | 발행 8토픽이 계약 스키마·카탈로그와 일치한다 | `kafka-topic-gate.test.mjs` · `CardEventContractTest` |
| AC-14 | 승인 지연이 예산 안에 든다 | `AuthorizationLatencyTest` |
| AC-15 | 커버리지 LINE >= 90% | `./gradlew :card-service:jacocoTestCoverageVerification` |

> **AC-12 가 이 Seed 에서 가장 중요한 게이트다.** `InboundPortReachabilityTest` 는 "유스케이스를
> 구현하고 단위 테스트까지 붙였는데 아무 어댑터도 호출하지 않아 런타임에 존재하지 않는 기능"을 막는다.
> 그 주석이 인용하는 실제 사례 두 건이 **loan 담보 재평가**와 **card 명세서 개시**다 — 이 Seed 와
> 자매 Seed 가 다루는 바로 그 영역이다. 컴파일러도 단위 테스트도 "부르는 사람이 없다"를 보지 못한다.

**테스트 자산**: 60개 파일 = 단위·슬라이스 46 + Testcontainers 통합 14
(`CaptureIT`·`HoldExpiryIT`·`StatementBillingIT`·`ConcurrentAuthorizationIT`·`CardIssuanceLimitConcurrencyIT`·
`LimitRecalculationClampIT`·`DelinquencyAuthorizationIT`·`MemberRemovedSuspendsCardIT`·`ExpenseWorkflowIT` 등).
**Flyway**: 10개(`V2` ~ `V20260822010000`).

## Known Issues (발견만 기록 — 사양은 as-is 유지)

- **KI-1 ★ 재원을 두 번 쓸 수 있는 구조가 열려 있다.** 불변식 3이 스스로 적어 둔 그대로 —
  `R = 0.70` 은 위험을 **줄일 뿐 닫지 않는다**. deposit hold/offset 연계는 아직 `DESIGN` 이다
  (funding-offset Seed). 카드 한도가 담보로 삼은 정산금이 그대로 셀러에게 지급될 수 있다.
  → `recorded-not-fixed` (to-be 스펙 존재)

- **KI-2 `card.captured` 를 자기가 발행하고 자기가 소비한다.** 명세서 적재·지출품의 생성이 자기
  발행 토픽을 되받는 구조라, 브로커 지연이 곧 청구 지연이다. 같은 트랜잭션 안의 상태 변화가 아니라
  비동기 왕복이므로 **매입 직후 명세서를 조회하면 아직 반영 전일 수 있다**.
  → `by-design` (Outbox 경유 일관성 — 지연 관측 지표는 없음)

- **KI-3 `CardLimitPolicy` 생성자가 generic `IllegalArgumentException` 을 던진다**
  (`CardLimitPolicy.java:31,35`). card 도메인은 타입 예외 계열을 쓰는데 이 둘은 예외다.
  다만 이것은 **도메인 규칙 위반이 아니라 설정 오류**(주입값 검증)라 성격이 다르다.
  → `recorded-not-fixed` (경계 판단 필요)

- **KI-4 승인 거절 사유의 세부가 로그에만 남는다(불변식 14).** 정책 역추적을 막는 의도된 설계지만,
  **셀러가 "왜 거절됐는지" 스스로 알 수 없다**는 뜻이기도 하다 — 운영 문의가 로그 조회로만 풀린다.
  → `by-design-documented` (트레이드오프)

- **KI-5 `E` 등급의 haircut 이 0.00 이라 한도가 0 이 된다** (`ReputationGrade.java:15`).
  계정은 ACTIVE 인데 한도만 0 인 상태가 성립하며, 이는 `SUSPENDED` 와 사용자에게 구분되지 않는다
  (둘 다 카드가 안 긁힌다). 다만 거절 사유는 `LIMIT_EXCEEDED` vs `CARD_SUSPENDED` 로 갈린다.
  → `recorded-not-fixed`

- **KI-6 만료된 홀드의 뒤늦은 매입은 예외로 거부된다.** 홀드 만료 배치(매일 04:00)가 먼저 돌면
  홀드가 `EXPIRED` 가 되고, 그 뒤 도착한 VAN 매입은 `AuthorizationHold.capture` 의 가드
  (`ACTIVE`·`PARTIALLY_CAPTURED` 만 허용, `AuthorizationHold.java:84-87`)에 걸려
  `IllegalStateException` 으로 **실패한다**. 즉 **카드사는 매입했는데 우리 원장에는 안 남는** 상태가
  성립할 수 있고, 이 경우의 보정 절차가 문서화돼 있지 않다. 매입 자체는 멱등 처리되지만
  (`CaptureHoldService.java:62-65`) 그것은 재전송을 흡수할 뿐 만료를 되돌리지 않는다.
  → `recorded-not-fixed` (실측 확인 — 러너북 후보)
