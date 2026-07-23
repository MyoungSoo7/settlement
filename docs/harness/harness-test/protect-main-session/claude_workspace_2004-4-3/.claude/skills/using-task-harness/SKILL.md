---
name: using-task-harness
description: Meta skill for the task decomposition harness workspace (2004-4-3). Defines the main loop (handoff -> review gate -> route), the sub agent dispatch contract, and the deterministic boundary between the Python hook and the review LLM-judge. Triggers on "작업분해 하네스", "task harness", "리프 sub agent", "handoff review route", "회의록 1page 분업".
---

# Using Task Harness (메타 스킬)

이 메타 스킬은 작업을 sub agent 여러 명으로 분업해 같은 흐름으로 돌리는 협업 규약을 정의한다.
회의록 → 1page 같은 구체적 작업 규칙은 sibling 도메인 자료(`inputs/`)와 sub agent 정의
(`.claude/agents/`)에 분리되어 있다.

## 역할 분담

| 자리 | 역할 |
|---|---|
| 메타 (이 파일) | 메인 루프, sub agent 띄우는 계약, route 규약, hook과 LLM-judge의 결정론 경계 |
| sub agent (`.claude/agents/ask·build·review`) | 리프별 작업 규칙 — 무엇을 받고, 무엇을 하고, 무엇을 handoff로 돌려주나 |
| 그래프 (`inputs/agent-execution-graph.md`) | 노드·edge·route 한 장 — 4-2 산출물 |
| review gate (`inputs/review-gate.md`) | format → evidence → false-negative 단계 정의 — 4-3 산출물 |

## 메인 루프 (리프마다 똑같이 반복)

메인 세션은 상태판이고 sub agent는 소모품이다. 그래프 순서대로 리프를 하나씩 꺼내, 매번 같은
네 단계를 돈다.

1. **handoff 보내기** — `inputs/handoff-template.md` §1 골격을 채워 sub agent에 brief로 준다.
2. **handoff 회수** — sub agent가 결과를 handoff 한 장으로 `handoffs/`에 쓴다.
3. **review gate** — format → evidence → FN 보정으로 통과시킨다.
4. **route** — verdict로 다음 자리를 정한다.

루프는 도구와 분리한다. 아래 dispatch 예시는 도구가 바뀌면 갈아끼우고, 위 네 단계와 route 표는
그대로 둔다.

## sub agent 띄우는 계약 (지금 도구 = Claude Code)

- Task 도구로 `.claude/agents`의 subagent를 띄운다 (`subagent_type`: ask / build / review).
- prompt = 채운 handoff 한 장 + 검수 대상·근거 파일 경로.
- 도구가 바뀌어도(새 대화창에 복붙, 다른 CLI의 sub-agent) 보내는 것은 같다 — handoff 한 장.
- 도구와 무관하게 남길 것: node 실행 단위 / guard(format·evidence·FN) / 입력·출력 계약 / 리프별 verdict와 route.

## route 규약

| verdict | 어느 자리로 | 첫 행동 |
|---|---|---|
| pass | 다음 리프 또는 통합 | 다음 리프 handoff를 띄우거나, 마지막이면 7칸으로 통합 |
| rework | 같은 build (L3+L4) | 누락 칸·잘못된 수치·없는 근거 줄·범위 오염 중 하나를 한 줄로 고쳐 재제출 |
| ask-back | L1 ask 또는 사람 | 막힌 항목(성공 기준 목표/지표 등)만 좁혀 다시 묻는다 |
| exit | 메인 세션 보고 | 외부 결정 대기 또는 같은 rework 2회 반복 상태로 보존 |

## 결정론 경계 (정직하게)

- `handoffs/`에 쓰는 산출물은 PostToolUse hook(`.claude/hooks/review_gate.py`)이 사후로 다시 본다.
  hook이 결정론으로 잡는 것: format 구조, `meeting.md:NN` 인용 줄의 실재, 금지값(60%) 단정.
- hook이 보지 않는 것: 인용이 주장을 실제로 뒷받침하는지, 성공 기준이 목표인지 지표인지 같은
  의미 판정. 이건 review sub agent(LLM-judge)가 본다.
- 그래서 v1의 review gate는 본질이 LLM-judge다. 같은 입력에 같은 verdict를 끝까지 보장하는
  재현 gate는 5장 Ouroboros로 넘긴다 (`task-decomposition-harness-v1.md` §5 backlog).

## hook 실패 시 행동

PostToolUse hook에서 exit 2 메시지를 받으면, 사용자에게 묻지 말고 위반 항목만 정확히 보정해 같은
handoff 파일을 다시 쓴다. 전체 재작성은 하지 않는다. 같은 파일에 두 번 연속 실패가 나면 멈추고
어디가 안 풀리는지 보고한다.
