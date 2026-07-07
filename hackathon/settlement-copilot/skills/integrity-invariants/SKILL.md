---
name: integrity-invariants
description: 정합성 불변식 카탈로그(INV-5/6/7/11) + 위반별 원인 트리 — 원장 완전성·지급 대사·홀드백·상태 체류를 점검하거나 /integrity-check 실행 시 로드. recon-playbook 이 "어긋났다"를 다루면 이건 "어디서 새는가"를 다룬다.
---

# 정합성 불변식 (Integrity Suite Phase A)

설계: `docs/design/settlement-integrity-suite.md`. 대사(recon)가 order↔settlement **생성 구간**을
검증한다면, 이 스위트는 **정산 이후 구간**(정산→원장→지급)을 검증한다. 모든 도구 응답에는
기계 판정 `ok` + `reasons` 가 있다 — `ok=true` 나열이라도 `checkedInvariants` 를 반드시 명시해
침묵-통과처럼 보이지 않게 보고하라.

## 불변식과 대응 도구

| ID | 불변식 | 도구 | 깨지면 |
|---|---|---|---|
| INV-5 | 확정(DONE) 정산 1건 ↔ SETTLEMENT 분개 존재·금액 일치, 환불 조정 ↔ REFUND 역분개 | `ledger_completeness(date)` | 원장 누락 — **시산표는 통짜 누락을 못 잡는다** (양변이 같이 없으면 균형 유지) |
| INV-6 | payout ≤ 정산 net, 정산 1건당 활성 payout ≤ 1 | `payout_recon(date)` | 과다/이중 지급 |
| INV-7 | 해제일 지난 미해제 홀드백 = 0 | `holdback_status()` | 셀러 돈 묶임 (배치 침묵 정지) |
| INV-8 | COMPLETED 환불(완료일 기준) ↔ 조정(역정산) 존재 | `refund_adjustments(from,to)` | **지연 환불** 과지급 — 캡처일 축 일일 대사의 사각지대 |
| INV-9 | 금액 대사와 **건수 대사** 동시 통과 | `recon_run(date)` 의 건수 축 | +N/−N 상쇄 오류가 금액 대사를 통과 |
| INV-10 | outbox PUBLISHED = 컨슈머 processed (+DLT) | `event_accounting(from,to)` | 이벤트 유실 — 발행−소비 gap 으로 특정 |
| INV-11 | PROCESSING/SENDING/RUNNING/PENDING 장기 체류 = 0 | `stuck_states()` | stuck 정산·**이중지급 위험(SENDING)** |

종합 진입점: `integrity_check(date)` — INV-5/6/7/8/11 을 순회하고 `allOk` 를 준다.
`allOk=false` 인 항목만 개별 도구로 드릴다운하라. INV-9 는 `recon_run` 이,
INV-10 은 `event_accounting`(order+settlement 양측 필요)이 담당한다.
지연 환불 소급 탐지가 필요하면 `recon_run(date, window=7)` 로 과거 7일을 재대사하라.

## 위반별 원인 트리

### INV-5 위반 (ledger_completeness.ok=false)

```
missingSettlementIds 비어있지 않음
├─ ledgerOutboxFailed > 0        ⇒ 원장 태스크 실패 — last_error 확인, 원인 제거 후 재처리
├─ ledgerOutboxPending 적체       ⇒ LedgerOutboxPoller 정지 의심 (스케줄러 락/앱 다운) — incident-runbooks
└─ outbox 정상인데 분개만 없음     ⇒ enqueue 자체가 누락된 코드 경로 의심 — SettlementConfirmItemWriter 확인
amountMismatchedSettlementIds     ⇒ 반쪽 분개 or 수동 개입 — 해당 정산의 분개 rows 를 ledger_entries 로 직접 조회
missingReverseRefundIds           ⇒ 환불 조정은 생겼는데 역분개 누락 — enqueueReverse 경로/refundId null 여부 확인
pendingWithinGrace > 0            ⇒ 위반 아님 (비동기 정상 대기) — 몇 분 뒤 재확인만
```

### INV-6 위반 (payout_recon.ok=false)

```
overpaidPayouts                   ⇒ 최우선 — 환불로 net 이 줄었는데 payout 은 옛 금액인 케이스 의심.
                                    payout 상태가 REQUESTED 면 cancel 후 재생성 제안, COMPLETED 면 환수(chargeback) 경로
duplicatePayoutSettlementIds      ⇒ uq_payouts_settlement 이 있으므로 정상 경로론 불가능 — 수동 INSERT/마이그레이션 사고 의심
settlementsWithoutPayout          ⇒ 위반 아님 (payout 생성은 운영자/후속 배치) — 건수만 보고
```

### INV-7 위반 (holdback_status.ok=false)

```
overdueCount > 0
├─ lastReleasedAt 이 오늘 새벽    ⇒ 배치는 살아있음 — 특정 건만 락 경합/실패, 해당 id 로그 확인
└─ lastReleasedAt 이 오래됨       ⇒ HoldbackReleaseScheduler(03:00) 정지 — 스케줄러 락(shedlock)/앱 인스턴스 확인
```

### INV-8 위반 (refund_adjustments.ok=false)

```
missingRefundIds                  ⇒ 환불 완료됐는데 역정산 없음 — 과지급 상태.
├─ 해당 refund 의 payment.refunded 이벤트가 발행됐나 → outbox_status / order outbox 확인
├─ 발행됐는데 미소비 → event_accounting 으로 gap 확인, DLT 조사
└─ 소비됐는데 조정 없음 → 컨슈머의 adjustSettlementForRefund 경로 (refundId null 전달?) 코드 확인
truncated=true                    ⇒ 위반 아님 — 기간을 좁혀 재검 (완전 검사 아님을 보고에 명시)
```

### INV-9 위반 (recon_run.countDiscrepancy != 0)

```
금액 축 정상 + 건수만 어긋남      ⇒ ±상쇄 의심 — 같은 금액의 누락+중복 조합.
                                    order 캡처 건수 > 정산 건수 ⇒ 정산 누락 (멱등 3단 방어 로그 확인)
                                    order 캡처 건수 < 정산 건수 ⇒ 이중 정산 의심 (payment_id UNIQUE 가 막았어야 정상 — 심각)
```

### INV-10 위반 (event_accounting.ok=false)

```
gap > 0 (발행 > 소비)
├─ outbox_status: pending 적체    ⇒ 발행 지연 (아직 유실 아님 — 폴러 확인)
├─ DLT 에 있음                    ⇒ 소비 실패 — 원인 제거 후 DLT 리플레이
└─ 어디에도 없음                  ⇒ 진짜 유실 의심 — 브로커 retention/오프셋 확인, projectionbackfill 검토
```

### INV-11 위반 (stuck_states.ok=false)

```
stuckSendingPayouts               ⇒ 이중지급 위험 1순위. 재시도 전 반드시 펌뱅킹 거래 조회로
                                    실제 송금 여부 확인 — 성공이면 COMPLETED 마킹, 실패 확인 후에만 retry
overdueConfirmations              ⇒ 확정 배치 누락 — settlement_date 지난 미확정. 배치 실행 이력 확인
stuckSettlements (PROCESSING)     ⇒ 확정 배치 중단 크래시 의심
stuckPgReconRuns (RUNNING)        ⇒ 대사 실행 크래시 — run 을 FAILED 로 종료 처리 후 재실행 제안
stuckLedgerOutboxPending/FAILED   ⇒ INV-5 트리와 동일
```

## 철칙

- 이 스위트는 **탐지까지만**이다. 정정은 언제나 기존 운영 경로 — 조정(adjustment)/역분개(reversal)/
  DLT 리플레이/projectionbackfill — 로만 제안하라. settlements/ledger_entries/payouts 에 대한
  UPDATE/DELETE 는 어떤 경우에도 제안 금지 (ADR 0004/0007).
- grace window 안의 미처리(`pendingWithinGrace`)를 위반으로 보고하지 마라 — 비동기 정상 상태다.
- 보고 형식: 결론 한 줄 → 불변식 ID 별 판정 → 양측 숫자 병기 → 증거(id 목록) → 권고 조치 1개.
