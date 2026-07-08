---
description: 기간 원장 시산표 검증 + 불변식 위반 조사
argument-hint: "[from YYYY-MM-DD] [to YYYY-MM-DD]"
---

`ledger-invariants` skill 을 로드하라(skill 미지원 환경이면 `settlement-copilot/skills/ledger-invariants/SKILL.md` 를 직접 읽어라). 기간: $ARGUMENTS (비어 있으면 최근 7일).

1. MCP `ledger_entries(from, to)` 호출 — `trialBalance.balanced` 확인
2. **균형이어도 종료하지 마라** — 시산표는 분개가 통짜로 누락된 정산을 못 잡는다 (INV-5).
   기간 내 각 날짜(최대 최근 7일)에 대해 MCP `ledger_completeness(date)` 를 호출해
   missing/amountMismatched/missingReverse 가 전부 비었는지 확인한 뒤에야 "정상"으로 보고하라
3. 균형 + 완전성 통과면: 분개 수·기간 요약·검사한 날짜를 보고하고 종료
4. 불균형이거나 완전성 위반이면: skill 의 조사 순서(기간 경계 → 반쪽 분개 → 역분개 누락 → 조정 미반영)와
   `integrity-invariants` skill 의 INV-5 원인 트리대로 분석하고, 필요 시 `order_recon_totals(from,to)` 의
   completedRefunds 와 역분개를 대조하라

보고: 불변식 위반 유형별로 분류하고, 정정은 반드시 역분개 추가 절차로 제안하라 (UPDATE/DELETE 제안 금지).
