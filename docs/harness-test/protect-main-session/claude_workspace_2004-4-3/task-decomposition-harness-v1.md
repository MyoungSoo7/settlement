# Task Decomposition Harness v1 — meeting-to-1page 분업

4-2 Agent Execution Graph(`inputs/agent-execution-graph.md`)를, 메인 세션이 리프마다 sub agent를
띄워 실제로 돌리는 protocol로 옮긴 작업분해 하네스다. 메인 세션은 상태판이고 sub agent는 소모품이다.
메인이 handoff로 brief를 보내고, sub agent가 handoff로 결과를 돌려주고, review gate가 통과를
판정하고, route로 다음 리프를 정한다. 이 v1이 다루지 못하는 장기 상태·checkpoint·재현 가능한
결정론 gate는 5장 Ouroboros에서 다룬다.

## 0. 입력

| 항목 | 내용 |
|---|---|
| 하네스 이름 | meeting-to-1page 분업 하네스 v1 |
| 내 업무 입력 위치 | `inputs/meeting.md` (회의록 원문 36줄) |
| node = 리프 목록 (이름 그대로) | L1 결정 항목 추출 질문 · L3+L4 6칸 맵핑+확인 필요 항목 · L5 보존 표현 인용 검사 · L6 도메인 제약 위반 검사 |
| 리프별 담당 sub agent | L1 = ask · L3+L4 = build(보통 메인 inline) · L5 = review · L6 = review |
| sub agent를 띄우는 방법 (내 도구에서) | Claude Code Task 도구 + `.claude/agents`의 ask/build/review (§3) |
| dry-run으로 돌릴 리프 1개 | L6 도메인 제약 위반 검사 (review) — §4 |

## 1. Harness Contract

- **input**: 회의록 원문 한 건 (`inputs/meeting.md`).
- **output**: L5·L6 review를 통과한 1page 7칸 초안(6칸 본문 + 확인 필요 항목 1칸)과 리프마다 verdict 한 줄.
- **pass condition**: 리프마다 handoff 한 장에 근거·verdict·다음 행동이 있고, 그래프의 마지막 리프까지 pass로 닫힌다.
- **exit condition**: 같은 rework 사유가 2회 반복되거나, 외부 결정자 답이 verdict를 막거나, 입력이 부족할 때.

## 2. 메인 루프 (리프마다 반복)

그래프 순서대로 리프를 하나씩 꺼내, 매번 같은 네 단계를 돈다.

1. handoff 양식(`inputs/handoff-template.md` §1)으로 sub agent에 brief를 보낸다.
2. sub agent가 결과를 handoff 한 장으로 `handoffs/`에 돌려준다.
3. review gate(`inputs/review-gate.md`: format → evidence → false-negative 보정)로 통과시킨다.
4. route를 정한다 — pass=다음 리프 / rework=같은 build에 좁힌 재의뢰 / ask-back=ask sub agent 또는 사람 / exit=메인 보고.

### 루프 자체 (도구가 바뀌어도 그대로)

아래는 의사코드다. `dispatch`만 도구에 따라 달라지고, 나머지 네 줄은 도구와 무관하다.

```text
state = {}                                  # 메인 세션에만 쌓이는 리프별 verdict + route
for leaf in graph.order:                    # L1 -> (build) -> L5 -> L6 -> 통합
    brief    = fill_handoff(leaf, state)     # 1) 4-2 양식을 채운 한 장
    result   = dispatch(leaf.sub_agent, brief)   # 2) 도구로 sub agent 띄우기 (여기만 도구 의존)
    handoff  = collect(result)               #    sub agent가 handoffs/ 에 돌려준 한 장
    verdict  = review_gate(handoff)          # 3) format -> evidence -> FN 보정
    state[leaf] = verdict                     #    상태는 메인에 기록
    next_leaf = route(verdict)               # 4) pass / rework / ask-back / exit
    if verdict in (ask_back, exit): break    #    막히면 멈추고 보존
```

`dispatch` 한 줄을 빼면 이 루프는 Claude Code든, 다른 CLI의 sub-agent든, 새 대화창 복붙이든 똑같다.
sub agent에 들어가고 나오는 것은 언제나 handoff 한 장이다.

## 3. Runtime Surface (예시 — 바뀌어도 루프는 그대로)

| 항목 | 지금 내 도구에서 (Claude Code) | 도구와 무관하게 남길 것 |
|---|---|---|
| sub agent 띄우기 | Task 도구, `subagent_type`: ask / build / review (`.claude/agents`) | node 실행 단위 (리프 하나 = sub agent 한 명) |
| guard | `.claude/hooks/review_gate.py`(결정론 슬라이스) + review sub agent(의미 판정) | format · evidence · false-negative |
| handoff | `inputs/handoff-template.md` §1 골격, `handoffs/`에 기록 | node 입력·출력 계약 (한 화면 한 장) |
| 상태 메모 | `state/run-log.md` | 리프별 verdict + route |

### dispatch 예시 — Claude Code Task 도구

```text
Task(subagent_type="review", prompt="""
L6 도메인 제약 위반 검사를 맡아라.
검수 대상: inputs/handoff-L3-result.md
근거 원문: inputs/meeting.md
gate 단계: inputs/review-gate.md
verdict와 근거 줄, 다음 행동을 handoffs/handoff-L6-review.md 에 handoff로 써라.
""")
```

### dispatch 예시 — 도구가 바뀌면

| 도구 | 띄우는 법 | 보내는 것 (안 바뀜) |
|---|---|---|
| Claude Code | Task 도구 + `.claude/agents` subagent | 채운 handoff 한 장 |
| 다른 CLI의 sub-agent | 그 도구의 sub-agent 생성 명령 | 채운 handoff 한 장 |
| 새 대화창 | handoff를 새 세션에 복붙 | 채운 handoff 한 장 |

## 노드 (4-2 그래프 그대로, 이름은 리프 그대로)

| node = 리프 | sub agent | input (handoff in) | output (handoff out) | guard |
|---|---|---|---|---|
| L1 결정 항목 추출 질문 | ask | 회의록 원문 | 결정권자·일정·수치 출처·환불 기준 4종 질문 + 대상 | format |
| L3+L4 6칸 맵핑 + 확인 필요 항목 | build (보통 메인 inline) | L1 답 + 회의록 확정 묶음 + 보존 인용 3줄 | 6칸 + 확인 필요 항목 + 근거 줄 | format |
| L5 보존 표현 인용 검사 | review | build handoff + 보존 인용 3줄 | 통과/위반 줄번호 | evidence |
| L6 도메인 제약 위반 검사 | review | build handoff | 통과/위반 항목 + 다음 행동 | false-negative |

### Edges (리프 순서·의존)

```text
L1 결정 항목 추출 질문 → L3+L4 6칸 맵핑 → L5 보존 표현 인용 검사 → L6 도메인 제약 위반 검사 → 메인 통합
```

직렬이다. build는 L1 답 없이 시작하지 않고, review는 build 산출 없이 시작하지 않는다. L3+L4 build는
이 그래프에서 메인 세션이 직접 도는 inline build다 — `inputs/ac-tree.md` §5가 분업 가치 낮음으로
판정했기 때문이다. 회의록이 길거나 검수자가 별도 build 세션을 원하면 `build` sub agent로 띄운다.

### Routes (verdict → 다음 node)

| verdict | 어느 node로 | 첫 행동 |
|---|---|---|
| pass | 다음 리프 / 마지막이면 통합 | 다음 리프 handoff를 띄우거나, L6까지 통과하면 7칸 1page로 통합·보고 |
| rework | 같은 build (L3+L4) | 누락 칸·잘못된 수치·없는 근거 줄·범위 오염 중 하나를 한 줄로 고쳐 재제출 |
| ask-back | L1 ask 또는 사람 | 성공 기준이 목표 문장인지 측정 지표인지 확인, 지표면 측정 단위·기간을 정한다 |
| exit | 메인 세션 보고 | 외부 결정 대기 또는 같은 rework 2회 반복 상태로 보존 |

## 4. Dry-run (sub agent 1회 실제 실행)

L6 도메인 제약 위반 검사 리프에 review sub agent를 진짜 한 번 띄웠다. sub agent가
`inputs/handoff-L3-result.md`를 `inputs/meeting.md`·`inputs/review-gate.md`로 검수해 verdict를
`handoffs/handoff-L6-review.md`에 돌려줬다.

- 요청: build가 돌려준 6칸 handoff(`inputs/handoff-L3-result.md`)를 L6 도메인 제약으로 검수하고 route를 돌려줘.

| 항목 | 결과 |
|---|---|
| 돌린 리프 | L6 도메인 제약 위반 검사 (review) |
| 띄운 sub agent | Claude Code Task 도구, `subagent_type: review` (`.claude/agents/review.md`) |
| handoff 회수 | `handoffs/handoff-L6-review.md` — format pass / evidence pass / FN 보정에서 성공 기준 모호 |
| review verdict | **ask-back (재질문)** — `meeting.md:10`의 성공 기준이 목표 문장인지 측정 지표인지 미결정 (review-gate §3 규칙 6) |
| route + 다음 행동 | ask-back → L1 ask / 사람. 성공 기준을 목표로 둘지 지표로 쓸지 정하고, 지표면 측정 단위·기간을 정해 build에 되돌린다 |

검수 근거: 인용한 7개 줄(meeting.md:5, 10, 12, 15, 22, 23, 27)이 모두 36줄 안에 실재하고 주장을
직접 뒷받침했다. CS 문의 60%는 본문 6칸에 단정되지 않고 남은 질문에서만 다뤄졌으며, 합의 범위는
두 갈래로 유지됐다. 값은 다 맞지만 성공 기준 한 칸이 목표·지표를 구분해야 다음 단계가 가능해 ask-back에서 멈췄다.

결정론 사전 검수: 이 handoff는 PostToolUse hook(`.claude/hooks/review_gate.py`)의 format·인용 줄
실재·금지값 검사를 통과했다(exit 0). 같은 입력을 다시 넣으면 같은 규칙 6에 걸려 ask-back으로 떨어진다
(`inputs/review-gate.md` §8 replay와 동일). 단 이 재현은 review sub agent의 의미 판정에 기댄 것이라
v1에서 100% 보장하지 않는다 — 5장 과제.

## 5. v2 Backlog (5장으로)

이 v1은 한 세션 안에서 리프를 돌리고 상태를 `state/run-log.md` 한 장에 적는다. 거기서 막히는 자리가
5장 Ouroboros의 입력이다.

- **여러 세션에 걸쳐 남겨야 할 상태**: ask-back으로 멈춘 "성공 기준 목표/지표" 질문은 월요일 정기
  미팅·운영팀 회신 같은 외부 회차를 거쳐야 풀린다. 세션이 닫히면 `state/run-log.md`의 verdict가
  사라지지 않고 다음 회차로 이어질 상태 저장이 필요하다 — 어떤 스키마로 무엇을 남길까.
- **실패 시 돌아갈 checkpoint**: rework가 build로 돌아갈 때, build는 직전 어느 상태에서 다시
  시작해야 하나. handoff 한 장 말고 "여기까지는 통과했다"는 중간 지점을 어떻게 고정할까.
- **같은 입력에 같은 verdict를 보장할 결정론 gate**: v1의 review gate는 본질이 LLM-judge다. python
  hook은 format·인용 줄·금지값만 결정론으로 잡고, "인용이 주장을 뒷받침하나·성공 기준이 모호한가"는
  매번 sub agent가 다시 판정한다. 같은 handoff에 매번 같은 verdict를 끝까지 보장하려면 무엇을
  trace로 남기고 무엇으로 재생해야 하나 (5장 TraceGuard).
- **MCP로 올릴 후보**: review gate의 결정론 슬라이스(인용 줄 대조·금지값 검사)는 hook 안에 있어
  이 워크스페이스에만 묶여 있다. 다른 작업에서도 부를 수 있게 MCP 도구로 올린다면 어디까지를
  올릴까 — gate 전체인가, 결정론 슬라이스만인가.

## 자기 검수 체크리스트

- [x] 하네스가 내 업무 입력(`inputs/meeting.md`)에서 시작해 리프마다 같은 루프(handoff → review → route)를 돈다.
- [x] node가 일반명이 아니라 실제 리프 이름이다 (L1·L3+L4·L5·L6).
- [x] dry-run에서 sub agent를 진짜 한 번 띄워 verdict(ask-back)와 다음 행동이 `handoffs/handoff-L6-review.md`에 남았다.
- [x] sub agent 띄우는 방법(§3 dispatch 예시)과 루프(§2 의사코드)가 분리되어 있다.
- [x] handoff는 4-2 양식, guard는 4-3 review gate를 그대로 쓴다.
- [x] 5장으로 넘길 상태·checkpoint·결정론 gate·MCP 질문이 §5에 한 줄 이상씩 남아 있다.
- [x] 같은 입력을 두 번 넣으면 같은 구조의 결과(ask-back)가 나온다 — 단 의미 판정은 LLM-judge라 완전 재현은 5장 과제로 명시했다.
