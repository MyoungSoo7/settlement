---
description: 정산 온콜 진단 — 메트릭 순회 후 "지금 어디가 아픈지" 요약 + 러너북 기반 조치 제안
---

`incident-runbooks` skill 을 로드하고(skill 미지원 환경이면 `settlement-copilot/skills/incident-runbooks/SKILL.md` 를 직접 읽어라), 다음 순서로 상태를 순회하라:

1. MCP `outbox_status('order')` / `outbox_status('settlement')` — pending/failed 적체
2. MCP `projection_status()` — 뷰별 rows/amount 게이지
3. MCP `recon_run(오늘)` — 대사 matched 여부
4. MCP `pg_recon_runs(5)` — FAILED 실행 유무
5. MCP `stuck_states()` — 중간 상태 장기 체류 (payout SENDING 은 이중지급 위험 1순위 — 🔴 로 취급)

각 항목을 🟢/🟡/🔴 로 판정하고, 🔴 가 있으면 러너북의 해당 섹션 절차(진단→판단→조치)를 따라
**다음 조치 1개**를 구체적으로 제안하라. 데이터 생성/수정 조치는 직접 실행하지 말고
기존 운영 경로(DLT 리플레이, projectionbackfill, adjustment)로만 제안한다.

서비스가 내려가서 도구 호출이 실패하면 그것 자체가 최상위 발견 사항이다 — 실패한 엔드포인트와
에러를 그대로 보고하라.
