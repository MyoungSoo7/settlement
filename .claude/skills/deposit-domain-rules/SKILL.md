---
name: deposit-domain-rules
description: 셀러 예치금 원장 핵심 규칙 — 잔고 단일 진실원(total=available+locked, enforceInvariant), hold/offset 재원 이중사용 차단(locked 우선 상계·applied 추적), 만료 회수는 remainingAmount, referenceType 상수 불변(L3 멱등키), 모든 잔고 변경은 엔트리 기록. deposit-service 로직을 작성·수정·리뷰할 때 로드.
---

# 예치금 도메인 규칙 (deposit-service)

셀러 예치금 원장 — **잔고의 단일 진실원**. hold(재원 점유)·offset(상계)으로 재원 이중사용을 차단한다.
REST 는 `/api/deposits` 조회 + `/admin/deposits` 수기 콘솔, 컨슈머 2종(settlement.confirmed·payout.completed).

## 잔고 불변식 — `SellerDepositAccount` (순수 POJO, SCALE=2 HALF_UP)

```
total = available + locked   ·   available/locked/total ≥ 0
```

- **`enforceInvariant()`** 가 생성자 + 모든 상태 변경 메서드 끝에서 호출된다. 이 예외
  (`DepositInvariantViolationException`)는 로직 버그 신호다 — **절대 catch 로 삼키고 진행하지 말 것**.
- 잔고 이동의 **유일한 진입점**은 도메인 메서드 6종: `credit`·`debit`·`lock`·`release`·`captureFromLocked`·`debitAvailable`.
  부족은 전부 `InsufficientDepositException`(sellerId·operation 컨텍스트 포함), 비양수 금액은 `InvalidDepositAmountException`.
- 팩토리 봉인: `private` 생성자 + `open(sellerId)`/`rehydrate(...)` 뿐. 필드 직접 조작 경로를 만들지 말 것.
- DB 최후 방어선: `chk_deposit_accounts_total_eq_sum CHECK (total = available + locked)` + non-negative CHECK 3종.
- **모든 잔고 변경은 `deposit_entries` 엔트리 1건 이상을 남긴다**(CREDIT·DEBIT·HOLD·RELEASE·OFFSET) —
  엔트리 없는 잔고 변경은 대사 불가능한 돈이다.

## hold/offset 재원 이중사용 차단 — `DepositService.applyOffset` 순서가 곧 방어

1. `findBySellerIdForUpdate` **비관적 락을 먼저** — 락 없이 잔고를 읽으면 동시 상계가 같은 재원을 두 번 쓴다.
2. 활성 hold 가 있으면 `min(offsetAmount, hold.remainingAmount)` 를 **locked 에서 먼저** 상계
   (`hold.capture` + `account.captureFromLocked` 는 반드시 짝으로).
3. hold 잔여가 남으면 즉시 `release()` + RELEASE 엔트리 — locked 에 유령 잔량을 남기지 않는다.
4. 남은 청구액만 `debitAvailable` 로 — **`applied` 누적으로 차감분을 추적**해 같은 금액이 locked 와
   available 양쪽에서 두 번 빠지는 것을 막는다.
5. 그래도 부족하면 `DepositOffsetShortfall.open(...)` + `offset_shortfall` 이벤트 — 잔고를 음수로 만들지 않는다.

## 만료·부족분 처리의 함정

- **만료 회수는 `remainingAmount`** — `originalAmount` 를 쓰면 이미 계좌를 떠난 캡처분까지 되살아나 없는 돈이 생긴다.
- 종단 상태를 갈라 적는다: PARTIALLY_CAPTURED → `release()`, ACTIVE → `expire()` — EXPIRED/RELEASED 를 하나로
  뭉개면 카드 승인측과 대사할 때 사유 구분이 사라진다.
- shortfall **부분 해소 금지**: `resolveFromAvailable` 은 available < outstanding 이면 예외. `writeOff` 는
  **잔고를 건드리지 않는다**(상각 = 돈 이동이 아니라 판단의 기록).
- 만료 배치(`deposit-hold-expiry`, 매시 5분 KST, ShedLock PT10M)는 **건별 예외 격리** — 한 건 실패가
  전체를 되돌리지도, 조용히 삼켜지지도 않는다(log.error + 다음 회차 재시도).

## 멱등 3단 + referenceType 상수 불변

- L1 outbox `event_id` UNIQUE / L2 `processed_events(consumer_group, event_id)` /
  L3 `uq_deposit_entries_natural(account_id, entry_type, reference_type, reference_id, offset_sequence)`.
- **`REFERENCE_TYPE` 문자열 상수(`"SETTLEMENT"`·`"PAYOUT"`)는 변경 금지** — L3 UNIQUE 키의 일부라
  바뀌면 과거 행과 짝이 어긋나 이중 입금이 뚫린다.
- hold 멱등: `uq_deposit_holds_holder(holder_type, holder_reference)` — `placeHold` 동일 키 재요청은
  **기존 hold 를 그대로 반환**(에러 아님).
- `uq_deposit_entries_natural` 충돌(`DataIntegrityViolationException`)은 **409 DUPLICATE_DEPOSIT_ENTRY** 로
  번역한다 — 500 으로 흘리면 클라이언트가 무한 재시도한다(`DepositExceptionHandler` 정본).
- outbox/processed_events prune 함수는 **최소 7일 보존 가드**(미만 호출 시 RAISE EXCEPTION) — Kafka 재전송
  창 안에서 멱등키를 선삭제하면 리플레이가 이중 처리된다.

## 이벤트 경계

- 발행 5종(Outbox, aggregateType `"Deposit"`, 금액 `toPlainString()`): `balance_changed`·`hold_placed`·
  `hold_released`·`offset_applied`·`offset_shortfall`. 토픽명 하드코딩 금지(eventType 파생).
- **card.authorized/captured 는 의도적으로 미구독** — 페이로드에 sellerId 가 없어(cardAccountId 뿐,
  타입도 String vs Long) 대상 계좌를 특정할 수 없다. 계약을 고치기 전까지 hold/offset 의 유일한 입력은
  admin 콘솔이다 — 이 경계를 "구독 누락 버그"로 오판해 임의 매핑으로 잇지 말 것.

## 권한 (IDOR)

- 셀프서비스는 **sellerId 를 받지 않는다**: `GET /api/deposits/accounts/me` 가 `AuthPrincipal.userId()` 에서만
  파생(실패 403). 타 계좌 조회 `/accounts/{sellerId}` 는 ADMIN/MANAGER, `/admin/deposits/**` 는 ADMIN 전용.
- 계좌가 없으면 **0원 계좌를 지어내지 않고 404** — "계좌가 열린 적 없다"와 "잔고 0"의 구분을 보존한다.
- 증빙 게이트(`DepositProofGate`): credit/debit 최상단에서 검사, MISMATCHED/NEEDS_REVIEW → 422 무폴백.
  OCR 불가는 503(`DepositProofOcrUnavailableException`) — 추정으로 기표하지 않는다.

## 안티패턴 (발견 시 지적)

- `enforceInvariant()` 예외를 catch 후 진행 / 도메인 메서드를 우회한 필드 조작.
- 락 획득 전에 잔고를 읽거나, `hold.capture` 와 `account.captureFromLocked` 를 한쪽만 호출.
- 만료 회수에 `originalAmount` 사용 / EXPIRED 와 RELEASED 통합.
- `REFERENCE_TYPE` 상수 변경 · L3 UNIQUE 충돌을 500 으로 방치.
- shortfall 부분 해소 허용 · writeOff 에서 잔고 차감.
- card 이벤트를 임의 매핑으로 구독 배선 (sellerId 계약이 먼저다).
- 잔고 변경에 엔트리 미기록 / 음수 잔고 허용.
- 도메인에서 generic `IllegalArgumentException` throw (guard CAMPAIGN_SERVICES 에 deposit 포함 — 타입 예외 사용).
