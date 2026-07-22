# Task Decomposition Harness v1 — meeting-to-1page

## 1. Harness Contract

- input: `../2004-3-3-workspace/meeting.md` 회의록 원문
- graph: `../2004-4-2-task-breakdown-blueprint.md`
- handoff contract: `../2004-2-3-workspace/handoff-L3-build-to-main.md`의 공용 handoff 양식
- review gate: `../2004-3-3-workspace/deterministic-review-gate-L3.md`의 format -> evidence -> FN 보정 순서
- output: L5·L6 review gate를 통과한 1page 기획안 7칸 초안 + 리프마다 handoff·verdict·route
- pass condition: 리프마다 handoff, 근거, verdict, 다음 행동이 남고 L5·L6이 모두 pass다.
- exit condition: 같은 rework 사유 2회 반복, 외부 결정 대기, 또는 입력 부족으로 verdict가 바뀔 수 없는 상태다.

## 2. Nodes

| node = 리프 | sub agent | handoff input | handoff output | guard |
|---|---|---|---|---|
| L1 결정 항목 추출 질문 | ask | 회의록 원문 + 미결정 기준 4종(결정권자, 일정, 수치 출처, 환불 기준) | 질문 4개 + 대상자 + 근거 + 판정 | format, evidence, FN |
| MAIN INLINE BUILD | main | L1 handoff + meeting.md | 1page 초안 7칸 + 보존 표현 후보 + 근거 줄 | L3/L4는 sub agent node가 아님 |
| L5 보존 표현 인용 검사 | review | 1page 초안 7칸 + 보존 표현 후보 3줄 + 원문 줄번호 | 통과/위반 verdict + 위반 인용 + 다음 행동 | format, evidence, FN |
| L6 도메인 제약 위반 검사 | review | 1page 초안 7칸 + L1 질문·답 상태 + 근거 줄 | 통과/위반 verdict + 제약 위반 목록 + 다음 행동 | format, evidence, FN |

## 3. Main Loop

For each leaf node:

1. Build a brief from the graph node and the common handoff contract.
2. Spawn or open one disposable sub agent and send only that brief plus required evidence.
3. Receive exactly one handoff markdown from the sub agent.
4. Run the review gate:
   - format: required handoff sections, result fields, verdict, and next action exist.
   - evidence: every claim points to meeting evidence, source line, or quoted original text.
   - FN correction: preserve prose-equivalent claims; fail unsupported values, scope expansion, and unresolved decision claims.
5. Route:
   - pass: append verdict to `state/harness-state.md` and move to the next leaf or merge.
   - rework: send the same sub agent a narrowed correction brief for the failed fields only.
   - ask-back: route to `L1 결정 항목 추출 질문` or the human decision owner.
   - exit: stop the loop and report preserved state, blocker, and next checkpoint.

## 4. Runtime Surface

| 항목 | 지금 내 도구에서 예시 | 도구와 무관하게 남길 것 |
|---|---|---|
| sub agent 띄우기 | `multi_agent_v1.spawn_agent`에 `agent_type=worker` 또는 `agent_type=explorer`와 leaf brief 전달 | node 실행 단위는 leaf handoff 하나 |
| sub agent 재지시 | `multi_agent_v1.send_input`으로 failed field만 재의뢰 | rework brief는 실패 사유 하나로 좁힌다 |
| sub agent 회수 | `multi_agent_v1.wait_agent`로 final message를 받아 handoff 파일에 저장 | handoff markdown 한 장 |
| sub agent 종료 | `multi_agent_v1.close_agent`로 disposable agent 정리 | 메인 세션만 state를 소유 |
| guard | `python3 .codex/hooks/review_gate.py <handoff> --leaf L1` 같은 Python hook + 메인 검토 | format -> evidence -> FN 보정 |
| 상태 메모 | `state/harness-state.md`에 leaf, verdict, route, next action append | 리프별 verdict + route + checkpoint |

The current tool can change. The invariant is the contract: brief out, handoff in, gate verdict, route.

## 5. Routing Table

| verdict | route | first action |
|---|---|---|
| pass | 다음 리프 또는 MERGE | verdict와 다음 leaf를 state에 기록한다 |
| rework | 같은 leaf sub agent | gate 실패 사유 하나만 포함해 재의뢰한다 |
| ask-back | L1 ask 또는 사람 | 결정권자·일정·수치 출처·환불 기준 중 막힌 항목만 묻는다 |
| exit | 메인 세션 보고 | 외부 결정 대기 사유, 현재 handoff, 마지막 verdict를 보존한다 |

## 6. Graph Execution

```text
START
  -> L1 결정 항목 추출 질문
  -> MAIN INLINE BUILD: L3 6칸 맵핑 + L4 확인 필요 항목
  -> PARALLEL REVIEW
       |- L5 보존 표현 인용 검사
       `- L6 도메인 제약 위반 검사
  -> MERGE
       |- if L5 pass and L6 pass: final
       |- if rework: MAIN INLINE BUILD
       |- if ask-back: L1 결정 항목 추출 질문 / 사람
       `- if exit: 메인 세션 보고
```

## 7. Dry-run

Dry-run leaf: `L1 결정 항목 추출 질문`

Runtime used in this session:

```text
multi_agent_v1.spawn_agent(agent_type="worker", message="<L1 handoff brief>")
multi_agent_v1.wait_agent(targets=["<agent_id>"])
python3 .codex/hooks/review_gate.py handoffs/dry-run-L1-handoff.md --leaf L1
```

Result is recorded in:

- `handoffs/dry-run-L1-handoff.md`
- `state/harness-state.md`

Dry-run verdict:

| 항목 | 결과 |
|---|---|
| 돌린 리프 | L1 결정 항목 추출 질문 |
| 띄운 sub agent | `multi_agent_v1.spawn_agent(agent_type="worker")` disposable ask worker |
| handoff 회수 | 질문 4개, 대상자/팀, 원문 근거 포함 |
| review verdict | pass — format/evidence/FN failures none |
| route + 다음 행동 | pass -> MAIN INLINE BUILD. 4개 결정 질문을 7칸 초안의 결정 항목 섹션에 반영한다 |

## 8. v2 Backlog: Chapter 5 State/Checkpoint Questions

1. 여러 세션에 걸쳐 반드시 남겨야 할 최소 상태는 leaf별 handoff 원문, verdict, route, next action이면 충분한가?
2. rework 2회 반복을 판단할 checkpoint key는 leaf id + failed guard + failed field로 잡아도 되는가?
3. 같은 입력에 같은 verdict를 보장하려면 Python hook 규칙을 어느 수준까지 hard-code하고, 어느 부분을 LLM review로 남겨야 하는가?
4. MCP로 올릴 후보는 `spawn_leaf`, `save_handoff`, `review_gate`, `route_verdict`, `checkpoint_state` 중 무엇부터인가?
5. L5와 L6 병렬 review 중 하나만 pass일 때 partial pass 상태를 어떻게 저장하고 재사용할 것인가?
