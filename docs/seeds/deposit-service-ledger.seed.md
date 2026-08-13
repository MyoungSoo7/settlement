# Seed — deposit-service 예치금 원장 as-is 사양

> 상태: CONFIRMED (settlement/account seed 와 동일 방식 — 역산 결정화)
> 자매 Seed: `account-service-gl-core`(전사 GL), `card-service-funding-offset`(재원 소비측)

## Goal (한 줄)

**deposit-service(셀러 예치금 원장 — 잔고 단일 진실원, hold/offset 으로 재원 이중사용 차단)의 현행 동작을
실행 가능한 게이트에 매핑된 불변 사양으로 결정화해, 회귀 기준선 · 계약 드리프트 게이트 ·
면접/포트폴리오 문서 · 후속 기능 베이스로 쓴다.**

## 범위

**포함**

- 잔고 3필드 불변식(`total = available + locked`, 모두 `>= 0`)과 2중 강제(도메인 → DB CHECK)
- hold 상태머신(ACTIVE·PARTIALLY_CAPTURED·CAPTURED·EXPIRED·VOIDED·RELEASED)과 부분 캡처
- 상계(offset) 충당 순서와 부족분(shortfall) 기록
- append-only 원장과 L3 멱등 자연키
- 조회 표면(`/api/deposits`) + 수기 콘솔(`/admin/deposits`)

**제외**

- Kafka 소비 경로 — **현재 컨슈머가 하나도 없다**(경계 사실로만 기재, 아래 KI-1)
- 만료 배치 운영 절차(부분 인덱스 존재 사실만)

## 핵심 불변식 (as-is, 파일:라인 근거)

경로 접두 `deposit-service/src/main/java/github/lms/lemuel/deposit/`

| # | 불변식 | 근거 |
|---|---|---|
| 1 | **잔고 항등식** — `total = available + locked`, 세 필드 모두 `>= 0`. 도메인이 1차, DB CHECK 가 최후 방어선 | `domain/SellerDepositAccount.java:15-16,30-50` + `V1__deposit_core.sql:23-26` |
| 2 | **출금은 available 을 넘지 못한다** — 초과 시 타입 예외 | `SellerDepositAccount.java:103-111` → `InsufficientDepositException` |
| 3 | **원장 append-only** — 모든 잔고 변경은 엔트리 1건 이상으로 기록된다(대사 가능성) | `V1__deposit_core.sql:66,81,93` (`chk_deposit_entries_amount_positive` + 테이블 주석) |
| 4 | **L3 멱등 자연키** — `(account_id, entry_type, reference_type, reference_id, offset_sequence)` UNIQUE 가 중복 기록을 DB 에서 차단 | `V1__deposit_core.sql:83-84` (`uq_deposit_entries_natural`) |
| 5 | **hold 는 홀더당 하나** — `(holder_type, holder_reference)` UNIQUE 로 같은 원인의 중복 hold 불가 | `V1__deposit_core.sql:47` (`uq_deposit_holds_holder`) |
| 6 | **부분 캡처 규칙** — `remaining` 이 0 이면 CAPTURED, 남으면 PARTIALLY_CAPTURED. `remaining <= original` 을 DB 가 강제 | `domain/DepositHold.java:80-89` + `V1:53-54` |
| 7 | **hold 상태 전이 강제** — ACTIVE 만 만료 가능, 그 외 전이는 상태 확인 후 거부 | `DepositHold.java:103-105,114-117,127-130` |
| 8 | **상계 충당 순서 고정** — ① 해당 hold 의 `locked` 에서 먼저 상계(`min(offsetAmount, hold.remaining)`) → ② 잔여 locked 는 release → ③ 그래도 부족하면 `available` 에서 직접 차감(`min(remaining, available)`) | `application/service/DepositService.java:146-167` |
| 9 | **부족분은 삼키지 않고 기록** — 충당 후 남은 금액은 `DepositOffsetShortfall` 로 남는다(`shortfall = requested − applied`) | `DepositService.java:186` + `domain/DepositOffsetShortfall.java:45-51` |
| 10 | **쓰기는 비관적 락 안에서** — 모든 쓰기 연산이 `@Transactional` + 락 획득 후 도메인 불변식 강제 | `DepositService.java:20-25` |
| 11 | **발행은 Outbox 경유만** — `aggregateType="Deposit"`, `DepositBalanceChanged` 등 eventType 라우팅 → `lemuel.deposit.*` | `adapter/out/event/DepositEventPublisherAdapter.java:21,27,46` |

## 이벤트 계약

**발행** — Outbox 경유(`SaveOutboxEventPort`). `aggregateType="Deposit"`, 대표 이벤트 `DepositBalanceChanged`
(페이로드: `sellerId`·`available`·`locked`·`total`·`triggerEventType`).

**소비 0** — `@KafkaListener` 가 하나도 없다. 정산 확정·payout·카드 승인 같은 상류 이벤트가 예치금에
자동 반영되지 않으며, 현재 유입 경로는 `/admin/deposits` 수기 콘솔뿐이다(KI-1).

## 수용 기준 (게이트 매핑)

| AC | 기준 | 게이트 |
|---|---|---|
| AC-1 | 잔고 항등식·비음수가 어떤 연산 후에도 유지된다 | `./gradlew :deposit-service:test` — `SellerDepositAccountTest` + DB CHECK 4종 |
| AC-2 | available 초과 출금이 타입 예외로 거부된다 | `SellerDepositAccountTest` (`InsufficientDepositException`) |
| AC-3 | hold 상태머신·부분 캡처 경계가 표와 일치한다 | `DepositHoldTest` |
| AC-4 | 상계 충당 순서와 부족분 기록이 일치한다 | `DepositServiceTest` · `DepositOffsetShortfallTest` |
| AC-5 | 동일 참조 중복 기록이 원장에 쌓이지 않는다 | `uq_deposit_entries_natural` (L3) · `DepositEntryTest` |
| AC-6 | 헥사고날 의존 방향 위반 0 | `DepositArchitectureTest` |
| AC-7 | 인바운드 포트가 모두 어댑터에서 도달 가능하다 | `InboundPortReachabilityTest` |
| AC-8 | 커버리지 LINE >= 90% | `./gradlew :deposit-service:jacocoTestCoverageVerification` |

## Known Issues (발견만 기록 — 사양은 as-is 유지)

- **KI-1** ~~**컨슈머 미배선**~~ → **해소됨**(2026-08-13 확인, PR #229 / `98d525b3e`). Seed 작성 시점엔 `@KafkaListener`
  가 0건이라 잔고가 `/admin/deposits` 수기 입력에만 의존했으나, 이후 컨슈머 2종이 배선됐다 —
  `settlement.confirmed`→입금(refType `SETTLEMENT`), `payout.completed`→출금(refType `PAYOUT`).
  소비측 계약 테스트(`DepositConsumerParsingTest`)도 함께 들어왔다. **단 hold/offset 은 여전히 콘솔 경로**다
  (card 승인·매입은 페이로드에 sellerId 가 없어 미구독). → `disposition: resolved-upstream` (잔여: hold/offset 자동화)
- **KI-2** 발행 이벤트에 **계약 스키마가 없다** — `shared-common/src/testFixtures/resources/contracts/events/`
  에 `lemuel.deposit.*` 스키마가 없어 양방향 계약 테스트(ADR 0024) 대상이 아니다.
  → `disposition: by-design` (2026-08-13 재평가). 저장소 정책은 **발행 전용 토픽을 소비자가 생길 때 계약 편입**
  하는 것이고(`SPEC.md` §5 "발행 전용" 절, insurance 9종·card 5종도 동일), `lemuel.deposit.*` 5종은 거기에
  명시돼 있다. 계약을 먼저 박으면 소비자가 실제로 필요로 하는 형태를 모른 채 고정하는 셈이다.
  **소비처가 생기는 시점이 트리거** — 그때 `event-contract-change` 절차로 편입한다.
- **KI-3** `DepositHold.place`/`capture` 가 금액 검증에 **generic `IllegalArgumentException`** 을 던진다
  (`DepositHold.java:57,85,89`). OO 게이트의 "금융 도메인 타입 예외" 관례와 어긋나지만 guard 의
  `OO-DOMAIN-GENERIC-IAE` 규칙 대상 서비스 목록(settlement·order·loan·investment·account·insurance)에
  deposit 이 없어 차단되지 않는다. → `disposition: recorded-not-fixed` (게이트 비대칭)
- **KI-4** hold 상태 전이 위반이 `IllegalStateException` 이다(`DepositHold.java:105,117,130`). 동작은
  일관되나 타입만 generic 이며, 이 예외는 공용 Kafka 에러 핸들러의 **즉시-DLT 분류**에 걸린다 — 소비 경로가
  붙으면 상태 위반 메시지가 재시도 없이 격리된다(의도된 결과이나 문서화된 적 없음). → `disposition: recorded-not-fixed`
