#!/usr/bin/env bash
# PostToolUse hook (v2) — 3장 check-1page-output을 review_gate precheck로 흡수.
#
# 3장 2003-4-3에서는 이 hook이 보존 표현·확인 필요 항목·60% 단정을 자체 검사했다.
# v2에서는 같은 결정론 검사를 4장 review_gate.py(deterministic_post_tool_review)로 위임한다.
# 한 곳(review_gate.py)이 format · meeting.md:NN 줄 실재 · 60% 단정을 결정론으로 잡는다.
#
# 트리거: Write|Edit 직후. 대상: handoffs/*.md.
# 입력: tool payload JSON(stdin)을 그대로 review_gate.py에 넘긴다(PostToolUse 모드).

HERE="$(cd "$(dirname "$0")" && pwd)"
GATE="$HERE/../../.codex/hooks/review_gate.py"

# stdin payload를 review_gate.py PostToolUse 모드로 전달.
# review_gate.py는 handoffs/*.md 가 아니면 조용히 exit 0, 위반이면 exit 2 + stderr.
exec python3 "$GATE"
