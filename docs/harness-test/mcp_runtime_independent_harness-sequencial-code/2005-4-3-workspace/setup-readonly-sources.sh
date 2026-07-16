#!/usr/bin/env bash
# setup-readonly-sources.sh — 4장·3장 산출물을 5장 workspace로 read-only 복사한다.
#
# 원본은 절대 수정하지 않는다. 4장 workspace는 source-of-record이고,
# 여기서는 필요한 파일만 5장 workspace 안으로 복사해 graph node가 직접 읽고 쓰게 한다.
#
# 멱등(idempotent): 여러 번 돌려도 같은 결과. 복사본만 갱신한다.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"                 # practice-materials 상위 (project root)
CODEX="$ROOT/practice-materials/chapter-04/codex_workspace_2004-4-3"
CH03WS="$ROOT/practice-materials/chapter-03/2003-4-3-workspace"

echo "[setup] project root      : $ROOT"
echo "[setup] 4장 codex source   : $CODEX"
echo "[setup] 3장 surface source : $CH03WS"

# fail-soft: 이 workspace는 이미 self-contained(inputs/·source/·contracts/·.codex/hooks/review_gate.py 포함)라
# setup 없이 'python3 harness_v2_server.py run' 이 바로 돈다. setup은 2~4장 원본을 가진 저자 환경에서
# 복사본을 재구성할 때만 쓴다. 원본 디렉터리가 없으면 친절히 안내하고 skip(0).
if [ ! -d "$CODEX" ] || [ ! -d "$CH03WS" ]; then
  echo "[setup] 2~4장 원본 디렉터리가 없다 — 이 workspace는 이미 self-contained라 setup이 필요 없다."
  echo "[setup] 바로: python3 harness_v2_server.py run --review-adapter local"
  exit 0
fi

# --- 1. 4장 read-only 시작 입력 → 5장 inputs/ ---
# meeting.md, handoff-template, review-gate 규칙, agent-execution-graph,
# 그리고 성공 기준이 '모호' 상태인 build 결과(handoff-L3-result) = ask-back 유발 입력.
for f in meeting.md handoff-template.md review-gate.md agent-execution-graph.md handoff-L3-result.md ac-tree.md; do
  cp "$CODEX/inputs/$f" "$HERE/inputs/$f"
  echo "[copy] inputs/$f"
done

# --- 2. 4장 실제 sub agent 산출물 → 5장 source/handoffs/ (replay adapter 소스 뱅크) ---
# 이 파일들은 4장에서 실제 ask/build/review 세션이 남긴 handoff다.
# replay 런타임 어댑터가 이 뱅크를 읽어 5장 handoffs/로 다시 쓴다(= 녹화 세션 재생).
for f in handoff-L1-ask.md handoff-L3L4-build.md handoff-L5-review.md handoff-L6-review.md final-1page-draft.md; do
  cp "$CODEX/handoffs/$f" "$HERE/source/handoffs/$f"
  echo "[copy] source/handoffs/$f"
done

# --- 3. 4장 상태판 → 5장 source/state/ (참조용, 5장 state/는 새로 쓴다) ---
for f in run-log.md harness-state.md session-start-context.md; do
  cp "$CODEX/state/$f" "$HERE/source/state/$f"
  echo "[copy] source/state/$f"
done

# --- 4. 4장 결정론 guard → 5장 .codex/hooks/review_gate.py (원본 그대로 복사) ---
cp "$CODEX/.codex/hooks/review_gate.py" "$HERE/.codex/hooks/review_gate.py"
echo "[copy] .codex/hooks/review_gate.py  (4장 결정론 guard, 수정 없음)"

# --- 5. 4장 Codex 런타임 표면 → 5장 .codex/ (agents/skills 계승) ---
for f in ask build review; do
  cp "$CODEX/.codex/agents/$f.toml" "$HERE/.codex/agents/$f.toml"
  echo "[copy] .codex/agents/$f.toml"
done
cp "$CODEX/.codex/skills/using-task-harness/SKILL.md" "$HERE/.codex/skills/using-task-harness/SKILL.md"
echo "[copy] .codex/skills/using-task-harness/SKILL.md"

# --- 6. 3장 session surface → 5장 .claude/ (계승, hook 본문은 v2 안내로 갈아끼움은 별도 파일에서) ---
cp "$CH03WS/.claude/skills/using-1page-harness/SKILL.md" "$HERE/.claude/skills/using-1page-harness/SKILL.md"
cp "$CH03WS/.claude/skills/meeting-to-1page/SKILL.md"   "$HERE/.claude/skills/meeting-to-1page/SKILL.md"
echo "[copy] .claude/skills/using-1page-harness/SKILL.md  (3장 메타 스킬)"
echo "[copy] .claude/skills/meeting-to-1page/SKILL.md     (3장 도메인 스킬, 7칸 output contract)"

echo "[setup] done. 원본은 수정하지 않았다. 복사본만 5장 workspace 안에 정렬했다."
