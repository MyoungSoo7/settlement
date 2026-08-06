---
description: 기간 원장 시산표 검증 + 불변식 위반 조사
argument-hint: "[from YYYY-MM-DD] [to YYYY-MM-DD]"
---

`ledger-invariants` skill 을 로드하라. 기간: $ARGUMENTS (비어 있으면 최근 7일).

1. MCP `ledger_entries(from, to)` 호출 — `trialBalance.balanced` 확인
2. 균형이면: 분개 수·기간 요약만 보고하고 종료
3. 불균형이면: skill 의 조사 순서(기간 경계 → 반쪽 분개 → 역분개 누락 → 조정 미반영)대로
   entries 를 분석하고, 필요 시 `order_recon_totals(from,to)` 의 completedRefunds 와 역분개를 대조하라

보고: 불변식 위반 유형별로 분류하고, 정정은 반드시 역분개 추가 절차로 제안하라 (UPDATE/DELETE 제안 금지).
