#!/usr/bin/env bash
# SessionStart hook (v2) — 하네스 v2 워크스페이스 세션 시작 안내.
#
# 3장 2003-4-3 session-start.sh를 계승한다. 다른 점: v2는 frozen contract와 route 규칙을
# 시작 컨텍스트로 주입한다. 사전 안내(SessionStart)와 사후 검수(review_gate precheck)를 나눈다.

cat <<'EOF'
[하네스 v2 워크스페이스 (2005-4-3) — 세션 시작 안내]

이 workspace는 meeting-to-1page 하네스 v1(4장)을 5장 capability graph로 승격한 v2다.
graph 한 줄:
  requirements_contract -> spec_contract -> session_surface -> structured_contract
  -> state_store -> fill_handoff -> dispatch_plan -> invoke_runtime_adapter
  -> collect_handoff -> review_gate -> review_parallel(L5 ∥ L6) -> merge_verdict -> route

frozen contract (실행 중 direction 불변, Ouroboros Seed 원칙):
  - contracts/requirements-contract.md  (2장 통합: reader/decision/success/constraints)
  - contracts/spec-contract.md          (3장 통합: 7칸 output contract + 고정·남김·질문)

금지선 (contract constraints):
  - 회의록·확인된 요구사항 밖 사실을 결론처럼 쓰지 않는다.
  - 미확인 수치(CS 문의 60%)를 본문 6칸에 단정하지 않는다 — 확인 필요 항목으로만.
  - 범위 밖 항목(전체 리뉴얼·결제 모듈 모달·환불 기준 명문화)을 해결 방향에 넣지 않는다.

route 규칙 (route 노드가 코드로 강제):
  - exit       : 7칸 충분 + guard 통과 + 불확실성 분리 (satisfice)
  - retry      : guard 위반, checkpoint -> return node, 사유 한 줄만 보정
  - ask-back   : 입력·목표 불명확, requirements_update -> spec 갱신 -> resume target
  - blocked exit : 같은 retry 사유 2회 또는 외부 회신 없음, last_good_checkpoint + pending 보존

런타임 guidance:
  - 메타 스킬  : .claude/skills/using-1page-harness/SKILL.md  (협업 규약)
  - 도메인 스킬: .claude/skills/meeting-to-1page/SKILL.md     (7칸 output contract)
  - Codex 미러: .codex/skills/using-task-harness/SKILL.md

결정론 precheck:
  - 3장 PostToolUse(check-1page-output)는 v2에서 review_gate precheck로 흡수됐다.
  - .claude/hooks/check-1page-output.sh 가 4장 .codex/hooks/review_gate.py 를 그대로 호출한다.
EOF
