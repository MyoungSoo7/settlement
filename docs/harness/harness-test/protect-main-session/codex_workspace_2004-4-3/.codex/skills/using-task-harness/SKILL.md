---
name: using-task-harness
description: Meta skill for the task decomposition harness workspace (2004-4-3). Defines the main loop (handoff -> review gate -> route), the sub agent dispatch contract, and the deterministic boundary between the Python hook and the review LLM-judge. Triggers on "작업분해 하네스", "task harness", "리프 sub agent", "handoff review route", "회의록 1page 분업".
---

# Using Task Harness

이 메타 스킬은 작업을 sub agent 여러 명으로 분업해 같은 흐름으로 돌리는 협업 규약을 정의한다.

## 역할 분담

| 자리 | 역할 |
|---|---|
| 메타 | 메인 루프, sub agent 띄우는 계약, route 규약, hook과 LLM-judge의 결정론 경계 |
| sub agent (`.codex/agents/ask·build·review`) | 리프별 작업 규칙 |
| 그래프 (`inputs/agent-execution-graph.md`) | node·edge·route |
| review gate (`inputs/review-gate.md`) | format -> evidence -> false-negative 단계 |

## 메인 루프

1. `inputs/handoff-template.md` 골격을 채워 sub agent에 brief로 준다.
2. sub agent가 handoff 한 장으로 `handoffs/`에 쓴다.
3. review gate를 format -> evidence -> FN 보정 순서로 돌린다.
4. verdict로 pass / rework / ask-back / exit route를 정한다.

메인 세션은 상태판이고 sub agent는 disposable 실행 단위다.

## Codex sub agent 계약

- Codex custom agent 파일은 `.codex/agents/*.toml`에 있다.
- 사용할 agent 이름은 `ask`, `build`, `review`다.
- prompt는 채운 handoff 한 장 + 검수 대상·근거 파일 경로다.
- 도구가 바뀌어도 남길 것은 node 실행 단위, guard, 입력·출력 계약, 리프별 verdict와 route다.

## Route

| verdict | 어느 자리로 | 첫 행동 |
|---|---|---|
| pass | 다음 리프 또는 통합 | 다음 리프 handoff를 띄우거나 마지막이면 7칸으로 통합 |
| rework | 같은 build 또는 같은 review | 실패 필드 하나만 좁혀 재의뢰 |
| ask-back | L1 ask 또는 사람 | 막힌 항목만 좁혀 다시 묻기 |
| exit | 메인 세션 보고 | 외부 결정 대기 또는 같은 rework 2회 반복 상태 보존 |

## 결정론 경계

- PostToolUse hook(`.codex/hooks/review_gate.py`)은 `handoffs/` markdown의 format, `meeting.md:NN` 줄 실재, `60%` 단정만 결정론으로 잡는다.
- 의미 판정은 review sub agent가 한다.
- 같은 입력에 같은 verdict를 끝까지 보장하는 재현 gate는 5장 Ouroboros로 넘긴다.
