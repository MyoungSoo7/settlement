---
description: 일일 대사 실행 + 불일치 원인 분류 (recon-playbook 절차)
argument-hint: "[date: YYYY-MM-DD, 생략 시 오늘]"
---

`recon-playbook` skill 을 로드하고, 그 조사 절차를 **순서대로** 수행하라. 대상 날짜: $ARGUMENTS (비어 있으면 오늘 날짜).

1. MCP `recon_run(date)` — matched 여부와 어긋난 축(결제/환불/정산) 확인
2. MCP `order_recon_totals(date)` — order 원천 기준값 확보
3. 불일치 시에만: `projection_status()` → `outbox_status('order')` → 필요 시 `ledger_entries`
   순으로 원인 트리를 타라. 앞 단계에서 원인이 특정되면 뒤 단계는 건너뛴다.

보고는 recon-playbook 의 보고 형식(결론 한 줄 → 양측 숫자 병기 → 증거 → 권고 조치)을 따르라.
어떤 경우에도 DB 직접 수정은 제안하지 마라 — 정정은 조정(adjustment)/역분개 경로만.
