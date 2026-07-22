#!/usr/bin/env bash
# 세션 시작 시 초기 환경 고정 hook.
#
# 트리거: SessionStart (Claude Code 세션이 시작되거나 /clear 직후).
# 역할: AI가 본격 작업에 들어가기 전에 "이 workspace에서 지켜야 할 규약"을
#       stdout으로 출력해 컨텍스트 상단에 박아 둔다.
#       PostToolUse hook이 사후 검수라면, SessionStart는 사전 안내다.
#
# 출력: stdout에 적은 텍스트는 Claude 세션의 system context로 주입된다.

cat <<'EOF'
[1page 하네스 워크스페이스 — 세션 시작 안내]

본 workspace에서 회의록을 1page 기획안 초안으로 옮기는 작업을 수행할 때,
다음 메타 스킬을 매 호출 직전에 읽고 시작한다.

  .claude/skills/using-1page-harness/SKILL.md

핵심 규약 (메타 스킬 본문에서 옮긴 요지):
  - 회의록 원문과 확인된 요구사항 밖의 사실은 결론처럼 쓰지 않는다.
  - "반드시 보존할 표현" 3줄은 출력에서 큰따옴표 인용으로 유지한다.
  - 미결정 항목은 "확인 필요 항목" 섹션으로 분리한다.
  - 1page 산출물 파일명에는 1page / one-page / 2003-4-3 중 하나를 포함한다.
  - Write/Edit 직후 PostToolUse hook이 자동 검사한다. exit 2 메시지를
    받으면 위반 부분만 정확히 보정해 같은 파일을 다시 Edit 한다.
EOF
