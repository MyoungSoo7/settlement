# Seed — settlement-service 월마감·정합성 as-is 사양

> **상태: CONFIRMED** (역산 결정화, 2026-08-22) · 정본 데이터: [`settlement-service-closing-integrity.seed.yaml`](settlement-service-closing-integrity.seed.yaml)
> 자매 Seed: [회계 코어](settlement-service-accounting-core.seed.md) · [세무](settlement-service-tax.seed.md) ·
> [지급후 회수](settlement-service-recovery.seed.md) — `closing`·`integrity` 는 회계 코어의 선언된 범위 밖이었다(감사 R-2).
>
> **as-is 원칙** — 결함은 교정하지 않고 Known Issues 로만 기록한다.

## 왜 두 슬라이스를 한 Seed 로 묶는가

둘 다 **개별 정산 1건이 아니라 기간 전체를 대상으로 하는 사후 검증**이다.
`integrity` 는 "지금 데이터가 서로 맞는가"를 매일 묻고, `closing` 은 "이 달은 이제 안 바뀐다"를
선언해 잠근다. 잠그기 전에 맞는지 봐야 하고, 잠근 뒤에는 틀린 것을 고칠 수 없다 —
**같은 축 위의 앞뒤 단계**라 한 사양으로 읽는 편이 낫다.

## Goal (한 줄)

**settlement-service 의 기간 단위 검증(정합성 스위트 8종)과 기간 확정(월마감 run·재마감 잠금)의
현행 동작을 실행 가능한 게이트에 매핑된 불변 사양으로 결정화한다.**

## 범위

| 포함 | 제외 |
|------|------|
| 마감 run 상태머신과 재시도 규약 | 원장 기간(`ledger_periods`) 자체의 개폐 규칙(→ 회계 코어) |
| 재마감 잠금 조건(원장 CLOSED × 마트 존재) | 정산·지급·원장 도메인 규칙(→ 회계 코어) |
| 마감 대상 기간 제약(완결된 과거 월만) | 대사 불일치의 원인 분류(→ `recon-playbook` 스킬) |
| 정합성 리포트 8종과 일일 모니터 배치 | MCP 도구 표면(운영 도구, 코드 밖) |

## 핵심 불변식 (as-is, 파일:라인 근거)

> 경로 접두: `settlement-service/src/main/java/github/lms/lemuel/`

### 월마감

1. **run 상태머신은 `RUNNING → COMPLETED | FAILED` 뿐이고 둘 다 종결**이다
   (`closing/domain/ClosingRunStatus.java:4-7,13-16`). **실패한 마감의 재시도는 상태 재개가 아니라
   새 run 생성**이며, 기간당 최신 run 이 upsert 된다.

2. **완결된 과거 월만 마감할 수 있다** — `period` 가 `currentMonth` 보다 앞서지 않으면
   `ClosingInvariantViolationException` (`closing/domain/MonthlyClosingRun.java:80-83`).
   당월·미래월 마감이 도메인에서 막힌다.

3. **재마감 잠금은 두 조건의 곱이다** — 원장 기간이 `CLOSED` **이고** `COMPLETED` 마트가 이미
   있으면 거부한다(`MonthlyClosingLockedException`). **회계 확정 후 보고 수치 변조를 막는 것**이
   목적이다. 원장이 `CLOSED` 라도 **마트가 없으면 최초 적재는 허용한다**
   (`closing/application/service/RunMonthlyClosingService.java:28-30`).

4. **`complete()` 는 집계 수치를 함께 받아 1회 갱신한다** — 셀러 수·정산 건수·미매핑 건수·
   대기 건수와 `ClosingTotals`. 음수 건수는 거부한다
   (`MonthlyClosingRun.java:109-119`). setter 가 없고 영속 복원은 `rehydrate` 전용이다.

5. **`rehydrate` 는 검증을 재실행하지 않는다** — 저장된 상태를 그대로 재구성한다
   (`MonthlyClosingRun.java:89-90`). 과거 규칙으로 통과한 레코드가 규칙 변경으로 되살아나지 않는다.

6. **id 는 1회만 부여된다** — 재부여 시도는 `IllegalStateException`
   (`MonthlyClosingRun.java:143-145`). write-once 인프라 가드라 도메인 예외 계열이 아니다.

7. **배치는 매월 1일 04:30 KST 에 직전 월을 마감하고 분산 락을 건다**
   (`closing/adapter/in/batch/MonthlyClosingScheduler.java:42-44`,
   `@SchedulerLock(name = "settlement-monthly-closing", lockAtMostFor = "PT30M")`).

8. **잠긴 기간은 정상 종료로 간주한다** — `MonthlyClosingLockedException` 은 로그만 남기고 스킵한다.
   이미 확정된 기간이라 재적재가 불필요하기 때문이다 (`MonthlyClosingScheduler.java:54-56`).

9. **실패는 다음 달 재시도가 아니라 운영자 콘솔 재실행에 맡긴다** — `FAILED` run 이 감사 기록으로
   남는다 (`MonthlyClosingScheduler.java:57-60`). 자동 재시도가 조용히 수치를 바꾸지 않게 한다.

10. **마감 실행은 감사로그를 남긴다** — `AuditAction.MONTHLY_CLOSING_EXECUTED` 에 기간·상태·
    셀러 수·정산 건수·미매핑 건수를 JSON 으로 기록한다 (`MonthlyClosingScheduler.java:47-52`).

### 정합성

11. **리포트 8종이 도메인 레코드로 존재한다** (`integrity/domain/`):
    `LedgerCompletenessReport`(원장 완전성) · `PayoutReconReport`(지급 대사) ·
    `PayoutBounceReconReport`(반송 대사) · `HoldbackStatusReport`(홀드백 상태) ·
    `StuckStateReport`(정체 상태) · `RefundAdjustmentReport`(환불 조정) ·
    `ProcessedEventCount`(멱등 처리 건수) · `ProjectionDiffReport`(프로젝션 차이).

12. **조회는 JDBC 어댑터가 직접 집계한다** (`integrity/adapter/out/persistence/IntegrityQueryJdbcAdapter`) —
    JPA 애그리거트를 거치지 않는다. 검증 쿼리는 도메인 상태를 바꾸지 않는 읽기 전용이다.

13. **프로젝션 대사는 order 를 통해 확인한다** — `OrderPaymentKeysAdapter`·`OrderCompletedRefundsAdapter`
    가 order 내부 대사 API 를 호출한다 (`integrity/adapter/out/recon/`).
    **양측이 자기 DB 만 읽는다** — cross-DB 조인 0 (ADR 0020 경계 유지).

14. **키 대조는 체크섬으로 한다** — `KeyChecksum` 포트. 전건 목록을 주고받는 대신 요약값을 비교해
    **대사 비용을 상수로 만든다**.

15. **일일 모니터가 매일 06:00 KST 에 전날 기준으로 돈다** — 분산 락 15분
    (`integrity/adapter/in/batch/IntegrityMonitorScheduler.java:50-53`).
    **월마감(04:30)보다 늦다** — 마감 결과까지 포함해 본다는 뜻이다.

16. **조회 표면은 ADMIN 전용 8경로**다 (`/admin/integrity/{ledger-completeness, payout-recon,
    payout-bounce-recon, holdback-status, stuck, refund-adjustments, processed-count, projection-diff}`,
    `integrity/adapter/in/web/IntegrityAdminController.java:34,48-104`).

## 수용 기준 (게이트 매핑)

| AC | 기준 | 게이트 |
|---|---|---|
| AC-1 | 당월·미래월 마감이 거부된다 | `MonthlyClosingRunTest` |
| AC-2 | 원장 CLOSED + 마트 존재일 때만 재마감이 잠긴다(마트 없으면 최초 적재 허용) | `RunMonthlyClosingServiceTest` |
| AC-3 | `RUNNING` 외 상태에서 `complete`·`fail` 이 거부된다 | `MonthlyClosingRunTest` · `ClosingRunStatusTest` |
| AC-4 | 음수 집계 건수가 거부된다 | `MonthlyClosingRunTest` |
| AC-5 | 잠긴 기간이 배치를 실패시키지 않는다(정상 스킵) | `RunMonthlyClosingServiceTest` — `MonthlyClosingLockedException` |
| AC-6 | 마감 결과가 영속·조회된다 | `MonthlyClosingPersistenceAdapterTest` · `GetMonthlyClosingServiceTest` · `SellerMonthlyClosingTest` |
| AC-7 | 정합성 리포트 8종이 기대한 수치를 낸다 | `IntegrityReportsTest` · `IntegrityAdminControllerTest` · `IntegrityPhase{A,B,C}IntegrationTest` |
| AC-8 | 프로젝션 대사가 cross-DB 조인 없이 수행된다 | `ProjectionReconciliationServiceTest` · `ProjectionDiffReportTest` · ArchUnit MSA 경계 |
| AC-9 | 배치가 분산 락을 건다(다중 인스턴스 중복 실행 0) | `scheduler-lock-gate.test.mjs` |
| AC-10 | 모든 인바운드 포트가 어댑터에서 도달 가능하다 | `InboundPortReachabilityTest` (ArchUnit) |
| AC-11 | 일일 모니터가 전날 기준으로 검사를 돌린다 | `IntegrityMonitorSchedulerTest` |
| AC-12 | 커버리지 LINE >= 90% | `./gradlew :settlement-service:jacocoTestCoverageVerification` |

**테스트 자산**: 14개 파일 = closing 6 + integrity 8(그중 `IntegrityPhase{A,B,C}IntegrationTest` 3종이 통합).

## Known Issues (발견만 기록 — 사양은 as-is 유지)

- **KI-1 정합성 검사가 판정만 하고 아무것도 막지 않는다.** 8종 리포트는 조회 표면과 일일 로그로만
  존재하며, **불일치가 발견돼도 마감을 막지 않는다**. 마감(04:30)이 모니터(06:00)보다 **먼저** 돌기
  때문에 순서상으로도 막을 수 없다 — 그날의 불일치는 이미 잠긴 기간에 대한 보고가 된다.
  → `recorded-not-fixed` (설계 판단 지점 — 마감 전 게이트로 승격할 후보)

- **KI-2 `FAILED` 마감의 재실행이 사람 손에 달려 있다.** 자동 재시도를 하지 않는 것은 의도지만
  (불변식 9), **아무도 콘솔을 열지 않으면 그 달은 마감되지 않은 채 지나간다.** 미마감 기간을
  알리는 경로가 없다.
  → `recorded-not-fixed` (알림 후보)

- **KI-3 `MonthlyClosingRun.assignId` 만 generic `IllegalStateException` 이다**
  (`MonthlyClosingRun.java:145`). closing 도메인은 타입 예외 계열을 쓰는데 여기만 다르다.
  다만 write-once 인프라 가드는 `IllegalStateException` 을 쓴다는 프로젝트 규약이 있어
  (`guard.mjs` OO-DOMAIN-GENERIC-IAE 주석) **규칙 충돌은 아니다**.
  → `by-design-documented`

- **KI-4 정합성 리포트에 임계·판정이 없다.** 리포트는 수치를 돌려줄 뿐 "이 정도면 이상"이라는
  기준이 코드에 없다 — 판단은 사람이나 MCP 도구(`settlement-copilot`) 쪽에 있다.
  같은 수치를 두 곳이 다르게 해석할 여지가 남는다.
  → `recorded-not-fixed` (임계의 정본 부재)

- **KI-5 마감 마트와 원장 기간이 서로 다른 잠금 주체다.** 재마감 잠금은 두 값을 **동시에** 봐야
  성립하는데(불변식 3), 두 상태를 한 트랜잭션이 소유하지 않는다. 원장 기간이 열려 있는 동안
  마트만 재적재되는 조합이 성립하며 그때의 정합성은 이 Seed 범위에서 확인되지 않았다.
  → `gap` (다음 감사 대상)
