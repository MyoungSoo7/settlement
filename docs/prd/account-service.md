# PRD — 계정계 GL (account-service)

> **문서 성격**: 구현된 코드에서 **거꾸로 역산한(reverse-engineered) 제품 요구사항 문서**다.
> 자매 문서 [`settlement-core.md`](./settlement-core.md)·[`card-service.md`](./card-service.md) 와 같은 규약을 쓴다 —
> 새 기능을 제안하지 않고, 이미 동작 중인 시스템이 *무엇을, 왜, 어떤 규칙으로* 하는지를 제품 관점으로 재진술한다.
>
> | 항목      | 값                                                                                       |
> | --------- | ------------------------------------------------------------------------------------------ |
> | 대상 범위 | `account-service`(8102, DB `lemuel_account`) — 전사 GL 집계 + 수신 3종(정기예금·적금·퇴직연금) |
> | 역산 기준 | 2026-08-13 `develop` 브랜치                                                              |
> | 근거      | 도메인 GL 코어 + banking 3종, 소비 17토픽, 컨슈머 12+, 테스트 36개 클래스                |
> | 범위 밖   | 정산·대출·투자의 도메인 규칙(각 서비스 소관) · 실제 송금 · 외부 회계 시스템 연동          |
> | 관련 문서 | [`SPEC.md`](../../SPEC.md) · `account-domain-rules`·`ledger-invariants` 스킬 · ADR 0026 · [`account-service-gl-core.seed.yaml`](../seeds/account-service-gl-core.seed.yaml) |

---

## 1. 배경과 문제

플랫폼의 돈은 **여러 서비스에 흩어져 움직인다** — 정산이 미지급금을 만들고, 대출이 채권을 만들고,
투자가 자산을 잡고, 카드가 재원을 묶는다. 각자 자기 DB 에 자기 숫자를 갖고 있다. 그러면 세 가지가 없다.

| 문제                | 구체적 손상                                                                        |
| ------------------- | ---------------------------------------------------------------------------------- |
| **전사 합계 부재**  | "지금 셀러들에게 줄 돈이 총 얼마인가"를 답할 곳이 없다                            |
| **대사 기준 부재**  | 서비스 간 숫자가 어긋나도 무엇이 맞는지 판단할 제3의 장부가 없다                   |
| **회계 표현 부재**  | 사건은 있는데 차변·대변이 없어 재무제표로 이어지지 않는다                          |

account-service 는 **전 서비스의 돈 사건을 복식부기 전표로 받아 적는 계정계**다. 핵심 설계 판단은 하나다 —
**아무것도 만들지 않고, 오직 받아 적는다.**

## 2. 목표 / 비목표

### 2.1 목표

| # | 목표 | 성공 기준 |
|---|---|---|
| G1 | 전사 돈 사건을 복식부기로 기록한다 | 모든 전표가 차1·대1·금액1 균형 |
| G2 | 같은 사건이 두 번 기록되지 않는다 | 3단 멱등(발행·소비·자연키) |
| G3 | 기록이 사후 조작되지 않는다 | append-only, 정정은 역전표 |
| G4 | 시산표가 항상 맞는다 | 차변 합 = 대변 합 |
| G5 | 통제계정이 보조부와 어긋나지 않는다 | 주기 대사 + 조회 API |
| G6 | 수신 상품(예금·적금·연금)을 같은 원장 위에서 운영한다 | 상품별 부채계정 분리 |

### 2.2 비목표

| # | 비목표 | 이유 |
|---|---|---|
| N1 | **이벤트 발행** | 계정계는 종단이다. 여기서 다시 사건을 만들면 순환이 생긴다 |
| N2 | 도메인 규칙 재판정 | 정산·대출의 규칙은 원천 서비스 소관. 여기는 결과만 받는다 |
| N3 | 전표 수정 | POSTED 는 불변. 정정은 반대 전표 |
| N4 | 실제 송금 | 지급 실행은 settlement payout 소관 |

## 3. 사용자

| 사용자 | 무엇을 위해 쓰는가 |
|---|---|
| **재무/회계 담당** | 시산표·계정별 잔액·전표 이력 |
| **운영자** | 통제계정 대사 결과 확인, 백필 |
| **플랫폼 서비스** | 집계 조회(대출·투자·정산 잔액) |
| **수신 상품 사용자** | 정기예금·적금·퇴직연금 개설·납입·수령 |

## 4. 제품 범위 — 기능 맵

| 영역 | 기능 |
|---|---|
| 전표 | 17토픽 소비 → 분개 자동 기표 |
| 계정 | 14계정(자산·부채·비용) 차/대 성격 고정 |
| 조회 | 계정별 잔액·전표, 집계(대출/투자/정산), 시산표, 통제계정 대사 |
| 대사 | 주기 잔액 대사 스케줄러 |
| 수신 | 정기예금·적금·퇴직연금 개설·납입·해지·수령 |
| 운영 | 백필, 감사 로그(파티션드) |

## 5. 핵심 유스케이스

### UC-1. 정산이 발생하면 미지급금이 잡힌다

1. settlement 가 `settlement.created` 를 발행한다.
2. account 가 소비해 **즉시지급분**과 **홀드백**을 각각 전표로 기표한다.
3. 전표는 차변 1 + 대변 1 + 금액 1 로만 만들어진다 — 생성자가 `private` 이라 **반쪽 전표를 표현할
   방법 자체가 없다.**

### UC-2. 확정은 기표하지 않고, 지급 시 상계한다 (Option A)

1. `settlement.confirmed` 는 **GL 무전표**다 — 확정은 회계 사건이 아니라는 판단(ADR 0026 Option A).
2. 실제 지급(`payout.completed`)에서 미지급금을 상계한다.
3. 이때 **분할 전기**가 일어난다 — `payable = min(지급액, max(0, 현재 SELLER_PAYABLE 잔액))` 만
   미지급금과 상계하고, 초과분은 **선지급 채권**으로 분리한다.

> 대응 크레딧이 없는 지급(수기 송금 등)이 통제계정을 음수로 모는 것을 막기 위해서다.

### UC-3. 같은 이벤트가 두 번 와도 전표는 하나다

1. **1단** — 원천의 `outbox_events.event_id` UNIQUE 가 중복 발행을 막는다.
2. **2단** — `processed_events(consumer_group, event_id)` PK 가 중복 처리를 막는다.
3. **3단** — `(source_topic, ref_type, ref_id)` UNIQUE 가 중복 전표를 DB 에서 막는다.
4. 어느 층이 뚫렸는지로 버그 위치를 특정할 수 있다.

### UC-4. 시산표와 통제계정을 확인한다

1. `GET /api/account/trial-balance` — 차변 합과 대변 합이 일치해야 한다.
2. `GET /api/account/control-recon` — 통제계정 잔액이 보조부 합과 맞는지 본다.
3. 주기 스케줄러(`BalanceReconScheduler`, 기본 10분)가 자동으로 대사한다.

### UC-5. 수신 상품을 운영한다

1. 정기예금·적금·퇴직연금을 개설하고 납입·해지·수령을 처리한다.
2. 상품별 부채계정(`TIME_DEPOSIT_LIABILITY`·`INSTALLMENT_SAVINGS_LIABILITY`·`RETIREMENT_PENSION_LIABILITY`)으로
   분리 기표한다.
3. 이자는 `INTEREST_EXPENSE` 로 잡힌다.

## 6. 기능 요구사항

| FR | 요구사항 | 강제 지점 |
|---|---|---|
| FR-1 | 전표는 차1·대1·금액1 이다 | `AccountEntry` private 생성자 + 팩토리 |
| FR-2 | 차변과 대변 계정이 같을 수 없다 | `chk_account_entry_accounts_distinct` |
| FR-3 | 금액은 양수이며 scale 2 로 정규화한다 | `chk_account_entry_amount` + Money |
| FR-4 | scale > 2 유입은 예외로 거부한다 | `ExcessivePrecisionEntryAmountException` |
| FR-5 | 전표는 UPDATE·DELETE 할 수 없다 | `trg_account_entry_append_only` |
| FR-6 | 자연키 중복 전표를 막는다 | `uq_account_entry_natural` |
| FR-7 | 이벤트를 발행하지 않는다 | `AccountArchitectureTest` 하드스톱 |
| FR-8 | 계정의 차/대 성격은 enum 에 고정된다 | `GlAccount`·`AccountSide` |
| FR-9 | 조정 전표는 허용된 leg 만 받는다 | `UnbalancedAccountEntryException` |
| FR-10 | `settlement.confirmed` 는 기표하지 않는다 | Option A |
| FR-11 | 지급은 잔액 범위에서만 상계한다 | `RecordPayoutService` 클램프 |
| FR-12 | 도메인 위반은 타입 예외를 쓴다 | `AccountDomainException` 계열 |

## 7. 도메인 규칙 (BR)

| BR | 규칙 | 근거 |
|---|---|---|
| BR-1 | **구성적 균형** — 균형은 검증하는 것이 아니라 **표현 불가능하게** 만든다 | 팩토리 전용 생성 |
| BR-2 | **원천 금액의 거울** — 조용히 반올림하지 않는다. 정밀도가 초과하면 거부한다 | scale 검증 |
| BR-3 | **append-only** — 회계는 지우지 않는다. 정정은 반대 전표 | 트리거 |
| BR-4 | **소비 전용** — 발행 금지가 ArchUnit 하드스톱. 단 DLT publish 프로듀서는 예외(비즈니스 이벤트가 아니라 실패 record 격리) | `AccountArchitectureTest` |
| BR-5 | **확정 무전표(Option A)** — 확정은 권리 확정이지 자금 이동이 아니다 | ADR 0026 |
| BR-6 | **통제계정 음수 방지** — 대응 크레딧 없는 지급은 채권으로 분리한다 | 분할 전기 |

## 8. 데이터 모델

| 테이블 | 역할 | 특기 |
|---|---|---|
| `account_entries` | 전표 | append-only 트리거, 자연키 UNIQUE, CHECK 3종 |
| `processed_events` | 멱등 2단 | `(consumer_group, event_id)` PK |
| `time_deposits`·`installment_savings`·`retirement_pensions` | 수신 3종 | 상품별 애그리거트 |
| `pension_transactions` 외 | 수신 거래 | 납입·수령 이력 |
| `audit_logs` | 감사 | 파티션드 + 런웨이 |

**계정과목 14종** — 자산(`CASH`·`LOAN_RECEIVABLE`·`CORPORATE_LOAN_RECEIVABLE`·`SECURED_LOAN_RECEIVABLE`·
`INVESTMENT_ASSET`·`SELLER_RECOVERY_RECEIVABLE`·`SETTLEMENT_SCHEDULED`), 부채(`SELLER_PAYABLE`·
`HOLDBACK_PAYABLE`·`WITHHOLDING_PAYABLE`·수신 3종 부채), 비용(`INTEREST_EXPENSE`).

## 9. 인터페이스

### 9.1 조회 REST

| 경로 | 설명 |
|---|---|
| `GET /api/account/accounts/{ownerType}/{ownerId}` (+`/entries`) | 계정별 잔액·전표 |
| `GET /api/account/aggregates/{loans\|investments\|settlements}` | 집계 |
| `GET /api/account/trial-balance` | 시산표 |
| `GET /api/account/control-recon` | 통제계정 대사 |
| `/internal/account` | 내부 호출 표면 |
| `/admin/backfill` | 백필 |

### 9.2 수신 REST

| 경로 | 설명 |
|---|---|
| `/api/banking/time-deposits` | 정기예금 |
| `/api/banking/savings` | 적금 |
| `/api/banking/pensions` | 퇴직연금 |

### 9.3 이벤트

**소비 17** — settlement 9(created·holdback 2·adjusted·canceled·withholding·confirmed·payout·recovery 2),
loan 6, investment 1. **발행 0.**

## 10. 비기능 요구

| NFR | 요구 | 현재 상태 |
|---|---|---|
| NFR-1 | 커버리지 LINE ≥ 90% | JaCoCo 게이트 |
| NFR-2 | 발행 금지 | ArchUnit 2룰 |
| NFR-3 | DLT 배선 | 공용 배선 + 도메인 예외 SPI 기여 |
| NFR-4 | 감사 로그 증가 대비 | 파티션 + 런웨이 |

## 11. 배치 (Asia/Seoul)

| 주기 | 작업 | 설정 키 |
|---|---|---|
| 10분(기본) | 잔액 대사 | `app.recon.balance.interval-ms` |
| 매월 1일 02:30 | 감사 파티션 런웨이 | `app.partition.ensure-cron` |

## 12. 역산에서 드러난 격차

### G-1. Option A 의 회계적 공백

`settlement.confirmed` 를 기표하지 않으므로, **확정과 지급 사이 구간의 시산표는 정산 상태와 시점이
어긋난다.** 미지급금이 `created` 시점 값 그대로 남아 있어, 그 구간에 재무제표를 뽑으면 확정된 조정이
반영되지 않은 숫자가 나온다.

### G-2. 통제계정 음수 방지가 부분적이다

분할 전기 클램프는 "**실지급이** 미지급금을 음수로 만들지 않는다"만 보장한다. 코드 주석이 스스로
인정하듯, **잔액을 읽지 않는 다른 전기**가 동시에 오면 통제계정이 음수가 될 수 있다. 즉 "SELLER_PAYABLE
은 절대 음수가 아니다"는 전역 불변식이 아니다.

### G-3. 집계 서비스와 상품 서비스가 한 배포 단위에 섞여 있다

계정계 GL(집계)과 수신 3종(상품 운영)이 같은 서비스·같은 DB 에 있다. 성격이 다른 두 책임이 같은
배포·같은 장애 반경을 공유한다. 계정과목도 그만큼 늘었다.

### G-4. 소비 전용이라 재발행 경로가 없다

DLT 로 격리된 분개 이벤트를 되살릴 자동 경로가 없다 — 원천 서비스가 다시 쏘거나 수기 보정해야 한다.
**그 절차가 문서화돼 있지 않다.** 전사 회계의 종단인데 유실 복구 절차가 없는 셈이다.

### G-5. 대사 실패의 처리 흐름이 없다

주기 대사가 불일치를 발견하면 무엇이 일어나는지(알람·인시던트·자동 보정)가 이 역산 범위에서
확인되지 않았다. 발견만 하고 흘려보내면 대사의 의미가 없다.

### G-6. 수신 상품의 회계 처리 깊이가 얕다

이자를 `INTEREST_EXPENSE` 로 잡지만, **미지급이자·경과이자(발생주의)** 처리가 있는지는 확인하지 않았다.
현금주의로만 처리하면 기간 손익이 왜곡된다.

## 13. 추적 항목

| # | 항목 | 상태 |
|---|---|---|
| T-1 | Option A 구간의 시산표 해석 지침 | 문서 없음 (G-1) |
| T-2 | 통제계정 전역 음수 방지 | 부분 보장 (G-2) |
| T-3 | GL 과 수신 상품의 배포 분리 | 미검토 (G-3) |
| T-4 | 분개 DLT 복구 러너북 | 없음 (G-4) |
| T-5 | 대사 불일치 후속 흐름 | 미확인 (G-5) |
| T-6 | 발생주의 이자 처리 | 미확인 (G-6) |
