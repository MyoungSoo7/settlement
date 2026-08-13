# PRD — 셀러 예치금 (deposit-service)

> **문서 성격**: 구현된 코드에서 **거꾸로 역산한(reverse-engineered) 제품 요구사항 문서**다.
> 자매 문서 [`card-service.md`](card-service.md)·[`settlement-core.md`](settlement-core.md) 와 같은 규약을 쓴다 —
> 새 기능을 제안하지 않고, 이미 동작 중인 시스템이 *무엇을, 왜, 어떤 규칙으로* 하는지를 제품 관점으로 재진술한다.
>
> | 항목      | 값                                                                                       |
> | --------- | ------------------------------------------------------------------------------------------ |
> | 대상 범위 | `deposit-service`(8112, mgmt 8113, DB `lemuel_deposit`) 전체 — 예치금 원장·hold·상계     |
> | 역산 기준 | 2026-08-13 `develop` 브랜치                                                              |
> | 근거      | 도메인 9개 클래스, 진입 어댑터 2종(공개 REST·관리 REST), Flyway V1~V3, 테스트 9개 클래스 |
> | 범위 밖   | 재원 산정 공식(card-service 소관) · 실제 송금 실행(settlement payout)                     |
> | 관련 문서 | [`../../../SPEC.md`](../../../SPEC.md) · [`deposit-service-ledger.seed.yaml`](../seeds/deposit-service-ledger.seed.yaml) · `card-service-rules`(재원 소비측) |

---

## 1. 배경과 문제

플랫폼은 셀러에게 **여러 방식으로 돈을 붙잡아 둔다** — 법인카드 승인 시 재원을 묶고, 대출을 실행하고,
투자를 집행한다. 각 서비스가 자기 방식으로 "이 셀러에게 얼마 남았나"를 계산하면 세 가지가 무너진다.

| 문제                | 구체적 손상                                                                  |
| ------------------- | ---------------------------------------------------------------------------- |
| **재원 이중사용**   | 카드가 잡아둔 금액을 대출이 모르면 같은 돈이 두 번 나간다                    |
| **잔고 정의 분열**  | "가용액"이 서비스마다 달라 같은 셀러가 다르게 보인다                         |
| **대사 불가**       | 잔고만 있고 변경 이력이 없으면 "왜 이 금액이 됐는가"를 되짚을 수 없다        |

deposit-service 는 **잔고의 단일 진실원**을 세우고, 모든 변경을 원장 엔트리로 남긴다. 핵심 설계 판단은
하나다 — **잡아둔 돈(locked)과 쓸 수 있는 돈(available)을 타입으로 분리한다.**

## 2. 목표 / 비목표

### 2.1 목표

| # | 목표 | 성공 기준 |
|---|---|---|
| G1 | 잔고가 항상 정합하다 | `total = available + locked`, 셋 다 `>= 0` |
| G2 | 재원이 두 번 쓰이지 않는다 | hold 로 선점 → 상계 시 그 hold 를 먼저 소진 |
| G3 | 모든 변경이 대사 가능하다 | 변경마다 원장 엔트리 1건 이상 |
| G4 | 같은 원인이 두 번 반영되지 않는다 | 자연키 UNIQUE(L3 멱등) |
| G5 | 충당 실패가 조용히 사라지지 않는다 | 부족분을 shortfall 레코드로 기록 |

### 2.2 비목표

| # | 비목표 | 이유 |
|---|---|---|
| N1 | **재원 산정 공식** | 카드 한도 산식은 card-service 소관. 여기는 잔고만 안다 |
| N2 | 실제 송금 | 지급 실행은 settlement payout 소관 |
| N3 | 이자·수익 계산 | 예치금은 보관 개념이지 금융상품이 아니다 |
| N4 | hold/offset 의 자동 이벤트 수신 | 입·출금은 배선됐으나(PR #229) hold/offset 은 콘솔 경로 — card 승인·매입 페이로드에 sellerId 가 없다 |

## 3. 사용자

| 사용자 | 무엇을 위해 쓰는가 |
|---|---|
| **셀러** | 내 예치금 잔고(가용/잠김) 확인 |
| **card-service** | 카드 승인 시 재원 hold, 매입 확정 시 상계 |
| **운영자** | 수기 입출금·hold·상계 조작(현재 유일한 유입 경로) |
| **정산/대출/투자** | (설계상) 확정 정산금 입금·집행 시 차감 |

## 4. 제품 범위 — 기능 맵

| 영역 | 기능 |
|---|---|
| 계좌 | 셀러당 1계좌, 잔고 3필드(available/locked/total) |
| 입출금 | credit(입금) / debit(출금, available 한도) |
| Hold | 선점(place) → 부분 캡처 → 만료·무효·해제 |
| 상계 | hold 우선 충당 → 잔여 release → available 직접 차감 |
| 원장 | append-only 엔트리(CREDIT/DEBIT/HOLD/RELEASE/OFFSET) |
| 부족분 | shortfall 레코드 기록 |
| 조회 | `/api/deposits` (JWT 주체 파생) |

## 5. 핵심 유스케이스

### UC-1. 카드 승인이 재원을 선점한다

1. card-service 가 승인 시 `POST /admin/deposits/accounts/{sellerId}/holds` 로 hold 를 건다.
2. `available` 이 줄고 `locked` 가 같은 만큼 는다 — **총액은 그대로**다.
3. `(holder_type, holder_reference)` UNIQUE 로 같은 승인에 hold 가 두 번 잡히지 않는다.

### UC-2. 매입이 확정되면 상계한다

1. 상계 요청이 들어오면 **① 해당 hold 의 locked 에서 먼저** `min(상계액, hold.remaining)` 을 캡처한다.
2. **② 잔여 locked 는 release** 해 available 로 되돌린다.
3. **③ 그래도 부족하면** available 에서 `min(잔여, available)` 을 직접 차감한다.
4. 그러고도 남은 금액은 **shortfall** 로 기록한다 — 삼키지 않는다.

> 이 순서가 G2(재원 이중사용 차단)의 본체다. 이미 잡아둔 돈을 먼저 소진해야 같은 돈이 두 번 나가지 않는다.

### UC-3. 출금은 가용액을 넘지 못한다

1. `debit` 요청이 `available` 보다 크면 `InsufficientDepositException` 으로 거부된다.
2. `locked` 는 출금 재원이 아니다 — 이미 다른 목적에 묶여 있다.
3. DB CHECK 4종이 도메인 검증을 통과한 뒤에도 마지막으로 막는다.

### UC-4. 셀러가 자기 잔고를 본다

1. `GET /api/deposits/accounts/me` — 셀러 식별자는 **JWT 주체에서 파생**된다(요청 파라미터 신뢰 금지, IDOR 방지).
2. 가용·잠김·총액이 함께 나온다 — "왜 이만큼밖에 못 쓰는가"가 보여야 하기 때문이다.

## 6. 기능 요구사항

| FR | 요구사항 | 강제 지점 |
|---|---|---|
| FR-1 | `total = available + locked` 를 항상 만족한다 | 도메인 + `chk_deposit_accounts_total_eq_sum` |
| FR-2 | 세 잔고 필드는 음수가 될 수 없다 | 도메인 + CHECK 3종 |
| FR-3 | 출금은 `available` 을 초과할 수 없다 | `InsufficientDepositException` |
| FR-4 | 모든 잔고 변경은 원장 엔트리를 남긴다 | `deposit_entries` |
| FR-5 | 엔트리 금액은 양수다 | `chk_deposit_entries_amount_positive` |
| FR-6 | 동일 참조의 중복 기록을 차단한다 | `uq_deposit_entries_natural`(L3) |
| FR-7 | hold 는 홀더당 하나다 | `uq_deposit_holds_holder` |
| FR-8 | 부분 캡처 시 remaining 이 0 이면 CAPTURED, 남으면 PARTIALLY_CAPTURED | 도메인 + `remaining <= original` CHECK |
| FR-9 | 상계는 locked → release → available 순으로 충당한다 | `DepositService.applyOffset` |
| FR-10 | 충당 부족분은 shortfall 로 기록한다 | `DepositOffsetShortfall` |
| FR-11 | 쓰기는 트랜잭션 + 비관적 락 안에서만 | `DepositService` |
| FR-12 | 조회 시 셀러는 JWT 주체에서 파생한다 | `DepositController` |

## 7. 도메인 규칙 (BR)

| BR | 규칙 | 근거 |
|---|---|---|
| BR-1 | **잔고 항등식은 2중 강제** — 도메인이 1차, DB CHECK 가 최후 방어선 | 마이그레이션 주석이 명시 |
| BR-2 | **locked 는 출금 재원이 아니다** — 잡아둔 돈은 쓸 수 있는 돈이 아니다 | `debit` |
| BR-3 | **충당 순서 고정** — 이미 선점한 재원을 우선 소진한다 | `applyOffset` |
| BR-4 | **부족분은 기록** — 못 채운 금액은 사건이지 무(無)가 아니다 | `shortfall = requested − applied` |
| BR-5 | **원장은 append-only** — 정정은 새 엔트리로. 대사 가능성이 목적이다 | 테이블 주석 |
| BR-6 | **hold 는 원인당 하나** — 같은 승인·대출로 두 번 잡히면 이중 선점이다 | `(holder_type, holder_reference)` UNIQUE |

## 8. 데이터 모델

| 테이블 | 역할 | 특기 |
|---|---|---|
| `deposit_accounts` | 셀러 계좌 | `seller_id` UNIQUE, 잔고 CHECK 4종 |
| `deposit_holds` | 선점 | 홀더 UNIQUE, 상태 6종 CHECK, `remaining <= original` |
| `deposit_entries` | 원장(append-only) | 자연키 UNIQUE(L3), 타입 5종, `source_hold_id` 역추적 |
| `deposit_offset_shortfalls` | 충당 부족분 | 요청·적용·부족 금액 + 상태 |
| `outbox_events` | 발행 | `lemuel.deposit.*` |

## 9. 인터페이스

### 9.1 조회 REST (JWT)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/deposits/accounts/me` | 내 잔고(주체 파생) |
| GET | `/api/deposits/accounts/{sellerId}` | 특정 셀러 잔고 |

### 9.2 관리 REST (수기 콘솔)

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/admin/deposits/accounts/{sellerId}/credits` | 입금 |
| POST | `/admin/deposits/accounts/{sellerId}/debits` | 출금 |
| POST | `/admin/deposits/accounts/{sellerId}/holds` | 선점 |
| POST | `/admin/deposits/accounts/{sellerId}/offsets` | 상계 |

### 9.3 이벤트

**발행** — `lemuel.deposit.*`(대표 `DepositBalanceChanged`: sellerId·available·locked·total·triggerEventType).
계약 스키마 미등록 — 저장소 정책상 **발행 전용 토픽은 소비자가 생길 때 편입**한다(`../../../SPEC.md` §5).

**소비 2** — `lemuel.settlement.confirmed`→입금(refType `SETTLEMENT`, refId settlementId) ·
`lemuel.payout.completed`→출금(refType `PAYOUT`, refId payoutId). 멱등키가 payoutId 인 이유는 계약상
`settlementId` 가 nullable 이라 자연키가 못 되기 때문이다. 소비측 계약 테스트 `DepositConsumerParsingTest`.

## 10. 비기능 요구

| NFR | 요구 | 현재 상태 |
|---|---|---|
| NFR-1 | 커버리지 LINE ≥ 90% | JaCoCo 게이트 |
| NFR-2 | 헥사고날 의존 방향 | `DepositArchitectureTest` |
| NFR-3 | 인바운드 포트 도달성 | `InboundPortReachabilityTest` |
| NFR-4 | 동시 상계에서 잔고 정합 | 비관적 락 + DB CHECK |

## 11. 배치 (Asia/Seoul)

| 주기 | 작업 |
|---|---|
| — | hold 만료 스캔용 부분 인덱스는 있으나, **만료 배치 자체는 이 역산 범위에서 확인하지 않았다**(→ G-4) |

## 12. 역산에서 드러난 격차

### G-1. ~~진실원을 채우는 자동 경로가 없다~~ → 부분 해소(2026-08-13, PR #229)

초안 시점엔 `@KafkaListener` 가 0건이라 유입이 `/admin` 수기 콘솔뿐이었다. 이후 **입금·출금 두 경로가
배선**됐다(`settlement.confirmed`→credit, `payout.completed`→debit). **남은 것은 hold/offset** — card
승인·매입 페이로드에 `sellerId` 가 없어 구독할 수 없고, 여전히 콘솔 경로다.

### G-2. ~~발행 이벤트에 계약이 없다~~ → 격차 아님(정책대로)

`lemuel.deposit.*` 의 JSON Schema 가 없는 것은 맞으나, 저장소 정책은 **발행 전용 토픽을 소비자가 생길 때
계약 편입**하는 것이다(`../../../SPEC.md` §5 "발행 전용" 절 — insurance 9종·card 5종도 같은 처지로 명시돼 있다).
소비자 없이 계약을 먼저 고정하면 실제 필요 형태를 모른 채 박는 셈이다. **트리거는 소비처 등장 시점.**

### G-3. ~~도메인 예외가 generic 이다~~ → 해소(2026-08-13)

타입 예외 3종(`InvalidDepositAmountException`·`InvalidDepositStateException`·
`DepositInvariantViolationException`)으로 전환하고 `guard.mjs` 의 `OO-DOMAIN-GENERIC-IAE` 대상에 deposit 을
편입했다. 후속으로 `deposit.domain.*` 을 INSTRUCTION 80% 엄격 목록에도 넣었다(`885c24d81`).
상속 대상이 곧 재시도 정책(IAE/ISE = 즉시 DLT)이라는 계약은 `DepositExceptionContextTest` 가 고정한다.

### G-4. hold 만료를 도는 주체가 없다 — **확인 완료, 실재하는 격차**

초안은 "확인하지 않았다"로 남겼다. 확인 결과: 부분 인덱스(`ACTIVE` + `expires_at`)도, 포트
`LoadDepositHoldPort.findActiveExpiredBefore` 도, 그 어댑터 구현도 있다. **없는 것은 호출자다** —
`@Scheduled` 0건이고 `DepositServiceApplication` 은 `@EnableScheduling` 을 의도적으로 끄고 있다(주석 명시).

만료된 hold 가 `locked` 를 계속 잡으므로 **가용액이 조용히 줄어든 채 유지**된다. 잔고가 틀리는 게 아니라
*덜 보이는* 방향이라 잔고 검증으로는 잡히지 않는다 — 셀러 입장에선 "왜 출금이 안 되지"가 된다.

### G-5. shortfall 을 해소하는 주체가 없다 — **확인 완료, 게다가 코드가 거짓을 적고 있었다**

`resolve()`/`writeOff()` 는 **프로덕션 호출자 0건**(테스트만 호출)이고 OPEN 건을 도는 스케줄러도 없다.
그런데 `DepositOffsetShortfall` 의 Javadoc 은 "`DepositShortfallRetryScheduler` 가 주기적으로 OPEN 건을
재상계 시도한다"고 단언하고 있었다 — **그런 클래스는 존재한 적이 없다.** 없는 동작을 있다고 적으면
부족분 적체를 아무도 보지 않게 되므로, 주석을 사실(해소 주체 없음)로 정정했다.

> G-4·G-5 는 같은 형태다 — **회수/해소의 재료는 다 있는데 그것을 도는 주체만 없다.** 둘 다 실패가
> 조용하다는 점도 같다(가용액 감소·부족분 적체 모두 예외를 던지지 않는다).

## 13. 추적 항목

| # | 항목 | 상태 |
|---|---|---|
| T-1 | 상류 이벤트 컨슈머 배선 | ✅ 입·출금 배선(PR #229) / hold·offset 은 잔여 (G-1) |
| T-2 | `lemuel.deposit.*` 계약 스키마 등록 | 정책대로 보류 — 소비처 등장이 트리거 (G-2) |
| T-3 | 도메인 타입 예외 전환 + guard 대상 편입 | ✅ 완료 + 게이트 편입 (G-3) |
| T-4 | hold 만료 회수 배치 | **없음 — 포트·인덱스만 있고 호출자 0건** (G-4) |
| T-5 | shortfall 해소 워크플로 | **없음 — resolve/writeOff 프로덕션 호출자 0건** (G-5) |
