---
description: ask-back으로 멈춘 1page 하네스 run을 회신(answers)으로 갱신해 이어 돌린다
argument-hint: "[answers=inputs/answers.md] [work_adapter] [review_adapter]"
allowed-tools: mcp__meeting-to-1page-harness__resume_meeting_to_1page, Bash(python3:*), Read
---

ask-back으로 멈춘 하네스 run을 2장 채택(answers)으로 갱신하고 resume_target부터 이어 돌린다.

인자: `$ARGUMENTS` (첫 번째=answers 경로 기본 `inputs/answers.md`, 이후 work_adapter / review_adapter)

1. 먼저 `state/pending-external.md`(있으면)를 읽어 어떤 질문에 회신하는지 확인한다.
2. `mcp__meeting-to-1page-harness__resume_meeting_to_1page` 를 위 인자로 호출한다.
   (fallback: `python3 harness_v2_server.py resume --answers <answers> --work-adapter <w> --review-adapter <r>`)
3. 반환 `route`로 보고한다:
   - **exit**: `handoffs/final-1page-draft.md` 7칸 + `requirements_update`로 채워진 성공 기준 metric을 보여준다.
   - **blocked exit**: 회신에 채택(☑)이 없어 갱신을 멈춘 것 — answers를 확인하라고 안내.
4. `run_log_tail`로 requirements_update → review → route 흐름을 정리한다.
