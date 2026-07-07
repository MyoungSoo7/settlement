---
description: 정합성 종합 점검 — 원장 완전성·지급 대사·홀드백·상태 체류 (INV-5/6/7/11) 순회 후 "돈이 새는가" 판정
argument-hint: "[date: YYYY-MM-DD, 생략 시 오늘]"
---

`integrity-invariants` skill 을 로드하라(skill 미지원 환경이면 `settlement-copilot/skills/integrity-invariants/SKILL.md` 를 직접 읽어라). 대상 날짜: $ARGUMENTS (비어 있으면 오늘 날짜).

`/oncall` 이 "인프라가 아픈가"를 본다면, 이 점검은 **"돈이 새는가"**를 본다.

1. MCP `recon_run(date)` — 생성 구간 확인: 금액 양축(INV-1/2) + 건수 축(INV-9).
   `countDiscrepancy != 0` 이면 금액이 맞아도 ±상쇄 의심이다
2. MCP `integrity_check(date)` — 정산 이후 구간 순회 (INV-5/6/7/8/11)
3. MCP `event_accounting(date-7, date)` — 발행↔소비 이벤트 회계 (INV-10)
4. `ok=false` 인 항목만 개별 도구(`ledger_completeness`/`payout_recon`/`holdback_status`/
   `stuck_states`/`refund_adjustments`)로 드릴다운하고, skill 의 해당 원인 트리를 따라라.
   `pendingWithinGrace` 는 위반이 아니다. 지연 환불이 의심되면 `recon_run(date, window=7)` 로 소급 재대사하라

보고 형식: 결론 한 줄(어느 불변식이 깨졌는지 또는 전부 통과) → 불변식 ID 별 🟢/🔴 표 →
위반 건의 양측 숫자 병기 + 증거 id → 권고 조치 1개.
전부 통과여도 "검사한 불변식(INV-1/2/5/6/7/8/9/10/11)과 기준일"을 명시하라 — 침묵-통과 금지.
어떤 경우에도 DB 직접 수정(UPDATE/DELETE)은 제안하지 마라 — 정정은 조정/역분개/리플레이 경로만.
