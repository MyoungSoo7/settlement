#!/usr/bin/env bash
# SessionStart hook — 1page 하네스 워크스페이스 세션 시작 안내.
#
# 트리거: Claude Code 세션이 시작되거나 /clear 직후.
# 역할: 본 workspace에서 지켜야 할 규약을 stdout으로 출력해
#       Claude system context 상단에 주입한다.
#       PostToolUse hook이 사후 검수라면, SessionStart는 사전 안내.

cat <<'EOF'
[1page 하네스 워크스페이스 (2003-4-3) — 세션 시작 안내]

본 workspace에서 회의록을 1page 기획안 초안으로 옮길 때,
다음 메타 스킬을 매 호출 직전에 읽고 시작한다.

  .claude/skills/using-1page-harness/SKILL.md

핵심 규약 (메타 스킬 본문 요지):
  - 회의록 원문과 확인된 요구사항 밖의 사실은 결론처럼 쓰지 않는다.
  - "반드시 보존할 표현" 3줄은 출력에서 큰따옴표 인용으로 유지한다.
  - 미결정 항목은 "확인 필요 항목" 섹션으로 분리한다.
  - 1page 산출물 파일명에는 1page / one-page / 2003-4-3 중 하나를 포함한다.
  - Write/Edit 직후 PostToolUse hook이 자동 검사. exit 2 메시지를
    받으면 위반 부분만 정확히 보정해 같은 파일을 다시 Edit 한다.

도메인 스킬:
  .claude/skills/meeting-to-1page/SKILL.md
EOF
