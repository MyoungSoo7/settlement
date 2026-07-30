# ADR 0030 — 계정계 통제계정 실체화 잔액 + 잔액 인식 라우팅 전역화

- 상태: **Proposed** (결정 대기 — 회계 오너 확정 필요 항목 3건)
  - **Phase 1(잔액 실체화 기반)은 선행 랜딩 완료** — ADR 이 "단독 랜딩 안전(읽기 소스 교체까지)"으로 규정한
    범위이며, 결정 포인트 1~3 과 무관하게 동작이 바뀌지 않는다(잔액 값의 의미·부호 규약 보존). Phase 2 는
    여전히 결정 확정 + HOLDBACK 초과분 재분류 계정 확정 이후에만 착수한다.
- 일자: 2026-07-28
- 관련: ADR 0026(payout 현금흐름 인식 Option ①), ADR 0029(세무 산출물), `account-domain-rules`·`ledger-invariants` 스킬
- 배경: ADR 0026 Option ① 구현(`2868fb9b2`) 후 코드리뷰 #5·#6 이 남긴 유보분. 커밋 `74dfa486a` 가
  "정석 해법이 동일한 큰 변경이라 지금은 **동작 변경 없이 과장된 주석을 실제 보장 범위로 정정**한다"며
  전역 불변식 강제를 명시적으로 후속 과제로 미뤘다. 본 ADR 이 그 후속이다.

## 컨텍스트

ADR 0026 Option ① 로 셀러 정산 GL 은 폐루프가 닫혔다(완전정산 시 통제계정 0). 그러나 그 폐루프는
**"모든 감액 사건이 정확히 한 번, 잔액을 초과하지 않게 도착한다"**는 전제 위에 서 있고, 그 전제를 강제하는
장치는 현재 한 곳(payout)에만 있다.

### 결함 1 — 음수 방지가 payout 국소 규칙이다

`SELLER_PAYABLE` 을 **차변**하는(= 부채를 줄이는) 레그는 5종인데, 잔액을 읽고 분기하는 건 payout 하나뿐이다.

| 레그 (`AccountEntry` 팩토리) | 전표 | 잔액 인식 |
|---|---|---|
| `payoutCompleted` / `payoutAdvanceReceivable` | DR SELLER_PAYABLE 또는 DR RECOVERY / CR CASH | **O** (`RecordPayoutService` 분할) |
| `withholdingAccrued` | DR SELLER_PAYABLE / CR WITHHOLDING_PAYABLE | ✗ |
| `settlementCanceledPayable` | DR SELLER_PAYABLE / CR CASH | ✗ |
| `settlementAdjusted(targetLeg=SELLER_PAYABLE)` | DR SELLER_PAYABLE / CR CASH | ✗ |
| `recoveryOffset` | DR SELLER_PAYABLE / CR SELLER_RECOVERY_RECEIVABLE | ✗ |

payout 이 잔액을 소진한 뒤(또는 동시에) 위 무조건 debit 이 도착하면 통제계정이 음수가 된다.
`RecordPayoutService` javadoc 이 이 한정을 이미 정직하게 기술하고 있다.

**0026 논의에서 빠진 확장**: 동일 결함이 `HOLDBACK_PAYABLE` 에도 있다 — `holdbackReleased`·`holdbackConsumed`·
`settlementCanceledHoldback`·`settlementAdjusted(targetLeg=HOLDBACK_PAYABLE)` 넷 모두 잔액 비의존 차변이며,
유보 잔액을 초과하는 해제·소진이 도착하면 같은 방식으로 음수가 된다. 즉 결함은 계정 하나의 문제가 아니다.

락도 국소적이다. `SellerAdvisoryLockAdapter` 는 `pg_advisory_xact_lock(hashtext('account:payout'), hashtext(sellerId))`
로 **payout-vs-payout 만** 직렬화한다. 무조건 debit 경로는 이 락을 잡지 않으므로 payout 과 병렬 진행하고,
그 결과 payout 이 읽는 잔액 자체가 stale 일 수 있다.

### 결함 2 — 잔액 조회가 O(셀러 전표 수)

`AccountEntryRepository.netBalanceByOwnerAndAccount` 의 WHERE 는 `(owner_type, owner_id)` 뿐이고 계정 구분은
SELECT 절 CASE 에 있다. 즉 **그 셀러의 전 계정·전 이력 전표를 훑어** 집계한다. `idx_account_entries_owner`
로 셀러 범위로 한정되므로 풀 스캔은 아니지만 비용은 이력 건수에 비례하고, 이 집계가 **advisory 락을 쥔 채**
실행되므로 락 보유시간도 이력에 비례한다 → 같은 셀러의 payout 처리량이 시간이 갈수록 선형으로 열화한다.

### 근본 원인 (두 결함은 하나다)

**"통제계정 잔액"이 어디에도 실체가 없다.** 필요할 때만 원장을 즉석 재합산하고, 그 재합산을 보호하는 락은
전체 기표 경로 중 일부에만 걸려 있다. 잔액을 실체화하면 조회가 O(1) 이 되어(결함 2) 모든 debit 레그에
잔액 인식 라우팅을 붙이는 비용이 감당 가능해지고(결함 1), 잔액 행의 행 잠금이 전 경로를 자동 직렬화한다.

## 결정 포인트 (오너 확정 필요)

1. **음수 잔액을 금지할 것인가, 허용하되 명시 인식할 것인가?**
   ADR 0026 과 현행 주석은 음수를 "셀러가 플랫폼에 빚짐(과다 원천징수/사후 조정)" 이라는 **회계적으로 의미 있는
   상태**로 본다. 그렇다면 정합적인 처리는 금지가 아니라 **자동 재분류** — 음수로 갈 부분을
   `SELLER_RECOVERY_RECEIVABLE`(채권)로 라우팅하는 것이다. payout 이 이미 정확히 그렇게 한다.
   금지(CHECK 위반 → DLT)를 택하면 정상적인 회계 사건에 운영 개입이 필요해지고 컨슈머가 멈춘다.
   → **재분류 권장.**
2. **잔액의 정본은?** 재합산(현행) / 실체화 스냅샷 / 둘 다(실체화 + 주기 대사).
   → **둘 다 권장.** 원장(`account_entries`)이 유일 진실로 남고, 실체화 잔액은 파생 캐시이며 대사로 증명한다.
3. **적용 범위**: `SELLER_PAYABLE` 만 vs `(owner, account)` 전체.
   → **전체 권장.** HOLDBACK_PAYABLE 도 같은 결함이고, 계정별 특수 취급은 매핑 드리프트의 원인이다.

## 제안 (세 옵션)

### 옵션 A — 잔액 인식 라우팅만 전역화 (실체화 없음)
무조건 debit 5종을 공용 라우터로 통과시키고, advisory 락 네임스페이스를 payout 전용에서 `(seller, account)`
공용으로 확대한다.
- 장점: 스키마 변경 0, 정합 리스크 최소(정본 하나 유지), 결함 1 해소.
- 단점: **결함 2 악화** — O(N) 재합산 경로가 1개에서 6개로 늘고 락 범위까지 넓어져 처리량이 더 나빠진다.

### 옵션 B — 실체화 잔액 + 라우팅 전역화 (**권장**)
`account_balances (owner_type, owner_id, account)` PK 테이블을 두고, 전표 insert 와 **같은 트랜잭션**에서 UPSERT 한다.
- **핵심 주의**: `insertIgnoreConflict` 의 반환값(신규=1, 중복=0)을 반드시 사용해야 한다. 현재
  `AccountEntryPersistenceAdapter.append()` 는 이 반환값을 **버린다**. 그대로 잔액 갱신을 얹으면 중복 수신 시
  전표는 멱등인데 잔액만 이중 반영되는 조용한 손상 경로가 생긴다 — 본 설계의 단일 최대 리스크.
- **잠금**: 잔액 행 UPSERT 자체가 행 잠금이므로 `(owner, account)` 단위로 **모든 기표 경로가 자동 직렬화**된다.
  advisory 락은 제거 가능해지고, 락 보유시간이 이력과 무관한 O(1) 이 된다.
- **조회**: PK 단건 lookup → O(1). 결함 2 해소.
- `account_entries` 의 append-only 트리거는 그 테이블 전용이라 별도 잔액 테이블 UPDATE 와 저촉되지 않는다.

### 옵션 C — Kafka 파티션 키를 sellerId 로 통일해 순서로 회피 — **기각**
토픽이 서로 다르면 파티션도 다르므로 토픽 간 순서는 어차피 보장되지 않는다. 6개 프로듀서의 키 전략을 바꾸는
계약 변경 비용만 치르고 근본 문제(잔액 미실체화)는 남는다.

## 권장 결정 (초안)

결정 포인트 1 = **재분류**, 2 = **실체화 + 주기 대사**, 3 = **`(owner, account)` 전체** → **옵션 B**.

## 구현 범위 (결정 확정 후)

**Phase 1 — 잔액 실체화 기반** (단독 랜딩 안전: 읽기 소스 교체까지만) — ✅ **완료**
> 구현: `V20260729130000__account_balances.sql`(테이블 + 재합산 백필) · `AccountBalance{JpaEntity,Repository}` ·
> `AccountEntryPersistenceAdapter`(삽입 1행일 때만 양 레그 델타 UPSERT / 잔액 PK 조회) ·
> `MaterializedBalanceIT`(실체화 == 원장 재합산, 중복 수신 잔액 불변). 부호 규약은 credit-positive 로
> 기존 `netBalanceByOwnerAndAccount` 식과 동일 — 그 쿼리는 Phase 3 대사의 정답지로 보존한다.
1. 마이그레이션: `account_balances` 생성 + 기존 전표 재합산 백필(`INSERT ... SELECT`, 단일 tx).
2. `AppendAccountEntryPort.append` → insert 가 실제로 1행을 넣었을 때**만** 잔액 UPSERT.
   **반환값 사용 회귀 테스트를 가드로 고정**(중복 수신 시 잔액 불변).
3. `LoadAccountEntryPort.sellerPayableBalance` 구현을 PK 조회로 교체 — 포트 시그니처는 불변, 어댑터만 교체.

**Phase 2 — 라우팅 전역화** (레그별 분할 랜딩 금지 — 아래 리스크 참조)
4. `BalanceAwareDebitRouter`: `(계정, 요청액)` → `[상계 전표, 초과 재분류 전표]` 분할. 지금 `RecordPayoutService`
   안에 있는 분할 로직을 이리로 승격하고, payout 은 이 라우터의 첫 소비자로 축소한다.
5. 무조건 debit 5종(+ HOLDBACK 계열 4종)을 라우터 경유로 전환.
6. advisory 락 제거(행 잠금으로 대체) — **5 완료 후에만**. 먼저 제거하면 보호 공백이 생긴다.

**Phase 3 — 검증** *(구현 완료 2026-07-30 — Phase 2 와 독립 랜딩)*
7. ✅ `/control-recon` 확장: `account_balances` vs 원장 재합산을 전 계정 대조, 불일치 보고
   (`TrialBalanceQuery.balanceRecon()` — FULL OUTER JOIN 으로 값 왜곡·캐시 행 유실·고아 캐시 행 3유형 검출,
   드리프트 상세는 |델타| 내림차순 상한 100 캡, 건수 정본은 count).
8. ✅ 정기 대사 배치(`BalanceReconScheduler`, 기본 10분) + Prometheus 게이지 3종
   `account.balance.recon.{drift.count, checked.pairs, last.success.epoch}` — drift.count 는 −1(첫 성공 전
   미검증)·0(정합)·N(오염)을 구별하고, 실행 실패는 예외를 삼키되 게이지를 건드리지 않아
   last.success.epoch 정체가 실패 알람 축이다(관측이 방어선이려면 관측 자체의 생존 신호가 필요).
   자동 정정 없음 — 정정은 원인 규명 후 Phase 1 백필 쿼리 재실행(운영 판단). 종합 판정은
   `/control-recon` 의 `healthy()`(원장 폐루프 ∧ 캐시 정합) — `balanced()` 는 원장 축만 본다.
9. ✅ IT: 중복 수신 잔액 불변(`MaterializedBalanceIT`) / 오염 3유형 검출(`BalanceReconIT`) / 백필 멱등.
   동시 payout+withholding 경합은 `PayoutConcurrencyIT` 가 커버.

## 미결 항목 (Phase 2 블로커)

- **HOLDBACK_PAYABLE 초과분의 재분류 대상 계정**이 미정이다. `SELLER_PAYABLE` 초과분은 `SELLER_RECOVERY_RECEIVABLE`
  로 보내면 되지만(payout 선례), 유보 잔액을 초과하는 소진·해제는 ① 즉시분(`SELLER_PAYABLE`)에서 흡수 ②
  회수채권 인식 중 어느 쪽이 회계적으로 맞는지 확정이 필요하다. **회계 오너 확정 전에는 Phase 2 착수 불가.**
- ADR 0026 열린 질문 ④ — 수동 payout(`settlementId=null`) 정책. 본 설계에서 초과 실지급은 자동 재분류되므로
  위험은 낮아지지만, "수동 송금이 회수채권을 만드는 게 의도인가"는 여전히 정책 판단이다.

## 리스크 / 트레이드오프

- **이중 정본 드리프트** — 실체화 잔액이 원장과 어긋나면 조용히 틀린 라우팅을 한다. Phase 3 대사 없이
  Phase 1·2 만 랜딩하는 것을 금지한다.
- **중복 삽입 시 잔액 이중 반영** — 원인은 하나(insert 반환값 미사용)이므로 가드 테스트로 고정 가능.
- **Phase 2 부분 랜딩 위험** — 레그를 쪼개 랜딩하면 일부만 재분류되어 같은 성격의 감액이 계정에 따라 다른
  의미로 적재된다. Phase 2 는 원자 랜딩(0026 의 "부분 배선 시 반쪽" 교훈과 동일).
- **선행 측정 권고** — 결함 2(성능)는 현재 데이터 규모에서는 아직 이론적일 수 있다. 착수 전 셀러당 전표 수
  분포를 실측해 Phase 1 의 실익을 확인할 것. 반면 결함 1(정합)은 규모와 무관하게 실재한다.

## 대안 검토 요약

| 옵션 | 채택? | 이유 |
|---|---|---|
| A — 라우팅만 전역화 | ✗ | 결함 1 은 풀리나 결함 2 를 악화. 락 범위 확대로 처리량 추가 손실 |
| B — 실체화 잔액 + 라우팅 전역화 | **✓(제안)** | 두 결함이 한 해법으로 수렴, 행 잠금이 전 경로를 자동 직렬화 |
| C — 파티션 키로 순서 보장 | ✗ | 토픽 간 순서는 어차피 미보장, 계약 변경 비용만 발생 |
| 현행 유지 | ✗ | `74dfa486a` 가 이미 "후속 과제로 유보" 로 명시 — 유보의 만료 시점이 본 ADR |
