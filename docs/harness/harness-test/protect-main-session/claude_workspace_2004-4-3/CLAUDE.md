# claude_workspace_2004-4-3 — 작업분해 하네스 v1 운영 지침

이 폴더는 4-2에서 그린 Agent Execution Graph를, 메인 세션이 리프마다 sub agent를 띄워 실제로
돌리는 하네스 v1이다. 회의록 한 건을 1page 7칸 초안으로 옮기는 흐름을 분업으로 돌린다.

## 한 줄 원칙

메인 세션은 상태판, sub agent는 소모품. 리프마다 같은 네 단계(handoff → 회수 → review gate → route)를 돈다.

## 메인 루프 (도구가 바뀌어도 그대로)

1. handoff 양식(`inputs/handoff-template.md` §1)을 채워 sub agent에 brief로 보낸다.
2. sub agent가 결과를 handoff 한 장으로 `handoffs`에 쓴다.
3. review gate(`inputs/review-gate.md`: format → evidence → false-negative)로 통과시킨다.
4. route: pass / rework / ask-back / exit (표는 `task-decomposition-harness-v1.md` §2).

## sub agent 띄우기 (지금 도구 = Claude Code)

- Task 도구로 `../../../../../.claude/agents`의 subagent를 띄운다.
  - `subagent_type: ask`   → L1 결정 항목 추출 질문
  - `subagent_type: build` → L3+L4 6칸 맵핑 (보통은 메인이 직접 inline build)
  - `subagent_type: review`→ L5 보존 표현 인용 검사 / L6 도메인 제약 위반 검사
- prompt = 채운 handoff 한 장 + 검수 대상·근거 파일 경로.

## 노드 (4-2 그래프 그대로, 이름은 리프 그대로)

| 리프 | sub agent | guard |
|---|---|---|
| L1 결정 항목 추출 질문 | ask | format |
| L3+L4 6칸 맵핑 + 확인 필요 항목 | build (보통 메인 inline) | format |
| L5 보존 표현 인용 검사 | review | evidence |
| L6 도메인 제약 위반 검사 | review | false-negative |

직렬: L1 → (build) → L5 → L6 → 메인 통합. build는 L1 답 없이 시작하지 않고, review는 build
산출 없이 시작하지 않는다.

## hook 두 개 (python)

| hook | 트리거 | 역할 |
|---|---|---|
| SessionStart (`.claude/hooks/session_start.py`) | 세션 시작·/clear | 위 규약을 컨텍스트 상단에 주입 |
| PostToolUse (`.claude/hooks/review_gate.py`) | Write/Edit 직후 | `handoffs` 산출물의 format·인용 줄 실재·금지값 단정을 결정론으로 검사. 위반 시 exit 2 |

hook에서 exit 2 메시지를 받으면 위반 부분만 정확히 보정해 같은 handoff를 다시 쓴다. 전체 재작성 금지.

## 결정론 경계 (정직하게)

review gate v1의 본질은 LLM-judge다. review sub agent가 의미(인용이 주장을 뒷받침하나, 성공
기준이 목표인지 지표인지)를 판정한다. python hook은 그 산출의 구조·인용 줄·금지값만 결정론으로
다시 잡는 사후 안전망이다. 같은 입력에 같은 verdict를 끝까지 보장하는 재현 gate는 5장 Ouroboros 몫이다.

## 폴더

- `task-decomposition-harness-v1.md` — 하네스 본체(계약·루프·runtime surface·dry-run·v2 backlog).
- `inputs` — 입력 체인(meeting·ac-tree·graph·handoff-template·review-gate·build handoff 결과).
- `handoffs` — sub agent가 돌려준 handoff가 쌓이는 곳 (런타임).
- `state/run-log.md` — 리프별 verdict + route 상태판.
