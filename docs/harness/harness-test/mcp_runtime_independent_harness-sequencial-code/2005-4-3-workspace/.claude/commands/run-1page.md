---
description: 회의록→1page 하네스 v2 capability graph를 한 번 실행 (graph-level MCP 툴 호출)
argument-hint: "[work_adapter=replay|claude] [review_adapter=local|live|mcp]"
allowed-tools: mcp__meeting-to-1page-harness__run_meeting_to_1page, mcp__meeting-to-1page-harness__run_full_meeting_to_1page, Bash(python3:*), Read
---

meeting-to-1page 하네스 v2 capability graph를 한 번 실행한다. 메인 세션은 graph를 직접 돌리지 않는다 —
graph-level MCP 툴 한 콜로 잠긴 순서(requirements_contract→…→route)를 코드가 돌리게 한다.

인자: `$ARGUMENTS` (첫 번째=work_adapter 기본 `replay`, 두 번째=review_adapter 기본 `local`)

1. `mcp__meeting-to-1page-harness__run_meeting_to_1page` 를 위 인자로 호출한다.
   (MCP 서버가 안 보이면 fallback: `python3 harness_v2_server.py run --work-adapter <w> --review-adapter <r>`)
   전체 run→ask-back→resume까지 한 콜로 검증해야 하면
   `mcp__meeting-to-1page-harness__run_full_meeting_to_1page` 를 `answers=inputs/answers.md`와 함께 호출한다.
2. 반환 `route`로 분기해 보고한다:
   - **ask-back**: `state/pending-external.md`를 읽어 무엇을 물어야 하는지(2장 질문 구조) 요약하고, 회신 후 `/resume-1page`로 이어가라고 안내.
   - **exit**: `handoffs/final-1page-draft.md`를 읽어 7칸 결과를 보여준다.
   - **retry / blocked exit**: `run_log_tail`로 사유를 설명한다.
3. `run_log_tail`의 node별 진행과 최종 route를 표로 정리한다. 추측으로 칸을 채우지 않는다.
