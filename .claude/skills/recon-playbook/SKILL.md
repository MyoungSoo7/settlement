---
name: recon-playbook
description: 대사(reconciliation) 불일치 원인 분류 트리 — 일일 대사·PG 대사 불일치를 조사할 때 로드. MCP 도구 조합 순서 포함.
---

# 대사 플레이북

## 대사 구조 (ADR 0020 — cross-DB 0)

- settlement 은 자기 DB(settlement_db)의 프로젝션·정산 합계만 읽고,
  order 는 자기 DB(opslab)의 결제/환불 합계를 `/internal/recon/*` 로 노출한다.
- 일일 대사: settlement `/admin/reconciliation?date=` → `ReconciliationReport` (matched 여부 포함).
- PG 대사: PG 정산 CSV 업로드 → 내부 결제 원장과 비교 → `ROUNDING_DIFF` 자동보정,
  나머지는 PENDING 큐 → 운영자 승인/거절 (승인 시 SettlementAdjustment 트리거).

## 조사 절차 (순서대로 — 앞 단계가 원인이면 뒤는 보지 마라)

```
1. recon_run(date)              → matched=false 확인, 어느 축(결제/환불/정산)이 어긋났는지
2. order_recon_totals(date)     → order 원천 숫자 확보 (기준값)
3. projection_status()          → settlement_projection_amount 가 order 원천과 다르면
                                  ⇒ 프로젝션 문제 (4로), 같으면 ⇒ 정산 계산 문제 (5로)
4. outbox_status('order')       → pending 적체 ⇒ 발행 지연 (폴러/브로커)
                                  pending 정상 + 프로젝션 부족 ⇒ 컨슈머 문제 (DLT/멱등 로그 확인)
5. ledger_entries(from,to)      → 시산표 균형·역분개 누락 확인 (ledger-invariants skill)
```

## 불일치 유형별 원인 트리

| 증상 | 유력 원인 | 확인 방법 |
|---|---|---|
| settlement 합계 < order 합계 | 프로젝션 lag / 이벤트 유실(DLT) | projection_status + outbox_status, DLT 적체 |
| settlement 합계 > order 합계 | 중복 처리 (멱등 뚫림) | `settlements.payment_id UNIQUE` 위반 로그, processed_events |
| 환불 축만 불일치 | 환불 이벤트 타이밍 (자정 경계) / 역정산 누락 | 대사 기준일 vs 환불 완료일 비교, settlement_adjustments |
| 소액(원 단위) 차이 | 라운딩 순서 차이 | money-safety skill — 건별 vs 총액 계산 순서 |
| PG 대사만 불일치 | PG 파일 지연 반영 / 수수료 계산 차이 | pg_recon_runs 의 type 분포 (AMOUNT_MISMATCH vs MISSING) |

## 보고 형식

조사 결과는 반드시 다음 구조로 보고하라:

1. **결론 한 줄** (원인 분류 + 확신도)
2. 어긋난 축과 금액 차이 (양측 원천 숫자 병기)
3. 각 단계에서 확인한 증거 (도구 출력 요약)
4. 권고 조치 (자동보정 가능 여부 / 운영자 승인 필요 여부 / 코드 수정 필요 여부)

주의: 대사 불일치를 "DB 직접 UPDATE 로 맞추자"고 제안하지 마라 — 정정은 언제나
조정(adjustment)·역분개 경로다.
