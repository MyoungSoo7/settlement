# Final Harness Graph — meeting-to-1page MCP 하네스

> 입력: `practice-materials/chapter-05/2005-4-1-output-ouroboros-core-logic-analysis.md`
> 보조 근거: `practice-materials/chapter-05/2005-2-3-output-runtime-independent-harness-graph.md`, `practice-materials/chapter-05/2005-3-3-output-loop-protocol.md`, `practice-materials/chapter-04/codex_workspace_2004-4-3/`, `practice-materials/chapter-04/claude_workspace_2004-4-3/`
> 목적: 4장의 Contract/Context/Control/Confidence를 5장의 capability node로 번역하고, MCP 안쪽 orchestration boundary와 runtime-specific adapter를 분리한다.

---

## 0. 입력 확정

| 항목 | 내용 |
|---|---|
| 하네스로 만들 업무 | 회의록 한 건을 1page 7칸 초안(6칸 본문 + 확인 필요 항목 1칸)으로 옮기는 `meeting-to-1page` 분업 하네스 |
| 시작 입력 | `inputs/meeting.md` 회의록 원문, leaf graph, handoff template, review gate 규칙 |
| 최종 산출물 | L5·L6 review를 통과한 7칸 1page 초안 + leaf별 verdict/route/run-log/checkpoint |
| 4C에서 가져올 구조 | Contract=handoff/output contract, <br />Context=`state/run-log.md`와 checkpoint, <br />Control=leaf dispatch/review/route loop, <br />Confidence=L5/L6 review gate |
| 필요한 capability | `structured_contract`, `structured_output`, `state_store`, `checkpoint`, `targeted_resume`, `skill_dispatch`, `tool_call`, `guard`, `evaluation`, `route` |
| MCP 안쪽 node | `fill_handoff`, `dispatch_plan`, `invoke_runtime_adapter`, `collect_handoff`, `review_gate`, `review_parallel`, `merge_verdict`, `route`, `checkpoint_state`, `targeted_resume` |
| 사람에게 남길 판단 | 어떤 회의록을 다룰지, 성공 기준의 목표/지표 결정, 회의록 밖 정책·수치 확정, 인용이 의사결정상 충분한지의 최종 채택, exit 선언 |

---

## 1. 4C Transfer

| 4C | 4장에서 만든 것 | 5장에서 대응되는 capability |
|---|---|---|
| Contract | `handoff-template.md` 기반 handoff 한 장, 1page 7칸 output contract, leaf별 입력·출력 양식. Codex/Claude v1 모두 "brief out, handoff in"을 불변 계약으로 둔다. | `structured_contract`, `structured_output`. MCP는 handoff 필수 section, verdict enum, next action, evidence field를 구조로 검증한다. |
| Context | `state/run-log.md`, `state/harness-state.md`, `handoffs/` 산출물, ask-back 회수 결과, leaf별 verdict. v1에서는 메인 세션이 상태판을 소유한다. | `state_store`, `checkpoint`, `targeted_resume`. MCP는 append-only run event와 `input/spec/execution/review` checkpoint를 저장하고, 외부 답이 오면 저장된 `resume_target`으로 복귀한다. |
| Control | `fill_handoff -> dispatch -> execute -> review_gate -> route` 메인 루프, leaf 순서 `L1 -> L3+L4 -> L5/L6 -> merge`, route 전이 `pass/rework/ask-back/exit`. | `orchestration`, `skill_dispatch`, `route`, `tool_call`. MCP는 순서·전이·retry counter·partial pass 보존을 강제하고, 실제 sub agent 실행은 runtime adapter로 위임한다. |
| Confidence | L5 보존 표현 인용 검사, L6 도메인 제약 위반 검사, 결정론 gate(format/evidence line/금지값 60% 단정), false-negative 보정. | `guard`, `evaluation`, `review_parallel`, `merge_verdict`. MCP gate가 결정론 슬라이스를 직접 판정하고, 의미 슬라이스는 review sub agent를 내부에서 호출해 verdict로 닫는다. |

읽기: 4C는 문서 규율이고 capability는 실행 가능한 노드다. Contract는 "무엇을 받거나 내보낼 수 있는가", Context는 "어디서 재개할 수 있는가", Control은 "다음 노드가 무엇인가", Confidence는 "통과라고 믿어도 되는가"로 번역된다.

---

## 2. Capability Graph

```text
input(meeting.md, leaf_graph, handoff_template, review_gate)
  -> structured_contract
       - lock handoff schema
       - lock 1page output contract
  -> state_store.load_or_init
       - run-log replay
       - latest checkpoint restore
  -> fill_handoff [structured_output]
       - leaf + prior verdict + source paths -> one brief
  -> dispatch_plan [skill_dispatch]
       - choose ask/build/review leaf role
       - choose required evidence slice
  -> invoke_runtime_adapter [tool_call]
       - Claude Task / Codex subagent / CLI / copied session
  -> collect_handoff [structured_output]
       - normalize handoff out
       - reject hidden conversation state
  -> review_gate [guard]
       - format check
       - meeting.md:NN line existence check
       - forbidden 60% overclaim check
  -> review_parallel [evaluation, tool_call]
       |- L5 citation-preservation review
       `- L6 domain/scope/FN review
  -> merge_verdict [guard]
       - preserve pass side
       - retry only failed side
       - detect regression against preserved passes
  -> route [checkpoint, targeted_resume]
       |- pass: next leaf or final exit
       |- retry: checkpoint -> return node
       |- ask-back: L1/human -> spec checkpoint -> resume target
       `- blocked exit: last_good_checkpoint + pending external
```

### Node-to-capability map

| node | capability | 책임 |
|---|---|---|
| `structured_contract` | `structured_contract`, `structured_output` | handoff와 1page contract를 실행 중 바꾸지 못하게 잠근다. |
| `state_store.load_or_init` | `state_store` | 기존 run-log와 checkpoint를 읽어 현재 leaf, retry count, pending question을 재구성한다. |
| `fill_handoff` | `structured_output` | leaf별 brief를 handoff 한 장으로 채운다. |
| `dispatch_plan` | `skill_dispatch` | 다음 leaf와 필요한 sub agent role을 고른다. 이 결정은 workflow layer다. |
| `invoke_runtime_adapter` | `tool_call` | 실제 Claude/Codex/기타 런타임 호출만 맡는다. 이 노드는 adapter boundary다. |
| `collect_handoff` | `structured_output` | sub agent 결과를 handoff out 한 장으로 회수하고, 대화 로그 같은 비계약 상태를 버린다. |
| `review_gate` | `guard` | deterministic guard(format, evidence line, forbidden value)를 매번 같은 방식으로 실행한다. |
| `review_parallel` | `evaluation`, `tool_call` | L5와 L6 의미 검수를 병렬로 호출한다. gate 도구 안쪽에서 LLM review를 쓴다. |
| `merge_verdict` | `guard`, `evaluation` | 한쪽 pass를 보존하고 실패한 검수만 재실행 대상으로 만든다. 이전 pass 퇴행을 차단한다. |
| `route` | `route`, `checkpoint`, `targeted_resume` | exit/retry/ask-back/blocked exit 중 하나만 남기고 복귀 지점과 상태를 저장한다. |

---

## 3. MCP Orchestration Boundary

MCP 안쪽은 "순서, 상태, guard, route"를 강제한다. 런타임별 sub agent 실행 명령과 사람의 의미 판단은 바깥으로 둔다.

| 안쪽 node | 코드가 강제할 것 | 바깥에 남길 것 |
|---|---|---|
| `structured_contract` | handoff 필수 section, verdict enum(`pass/rework/ask-back/exit`), next action field, evidence field, 1page 7칸 output contract | 이 업무가 정말 1page로 충분한지, 7칸 구조를 바꿀지의 제품 판단 |
| `state_store.load_or_init` | run event append/replay, checkpoint id, retry count, pending external flag, preserved passes | 원본 회의록 제공, 외부 회차에서 받은 결정값의 진위 |
| `fill_handoff` | leaf id와 직전 verdict를 한 화면 handoff brief로 묶기, 필요한 evidence path 누락 차단 | leaf brief 문장의 뉘앙스, 사람에게 묻는 표현 |
| `dispatch_plan` | leaf 순서와 role 매핑: L1=ask, L3+L4=build/main inline, L5/L6=review | build를 inline으로 할지 별도 agent로 분리할지에 대한 운영 선택 |
| `invoke_runtime_adapter` | adapter 요청·응답 envelope, timeout, exit code, artifact path 회수 | Claude Task, Codex CLI, 다른 CLI, 새 대화창 등 실제 실행 방식 |
| `collect_handoff` | handoff out 파일 존재, schema 정규화, 비계약 로그 제거 | sub agent가 작성한 의미 판단의 품질 자체 |
| `review_gate` | format, `meeting.md:NN` 줄 실재, 금지값 `60%` 본문 단정 여부, 앞 단계 실패 시 의미 검수 차단 | 인용이 주장을 충분히 뒷받침하는지의 최종 의미 판정 |
| `review_parallel` | L5와 L6을 독립 세션으로 병렬 실행, 둘 다 완료 대기, 한쪽 pass 보존, 실패한 쪽만 재검사 | 무엇을 위반으로 볼지에 대한 도메인 기준의 의미 |
| `merge_verdict` | verdict 충돌 해소 규칙, partial pass 보존, regression check, retry 사유 dedupe | 애매한 경우 사람에게 올릴지 바로 rework할지의 책임 판단 |
| `route` | `exit/retry/ask-back/blocked exit` 중 단일 route, checkpoint -> return node, ask-back resume target, blocked last_good_checkpoint | "여기서 멈추고 보고한다"는 최종 exit 선언 |

### Boundary rule

- MCP는 "판단 순서"와 "상태 장부"를 소유한다.
- MCP는 결정론 guard를 직접 소유한다.
- MCP는 의미 검수가 필요할 때 review sub agent를 안쪽에서 호출할 수 있다. 즉 review LLM은 gate 바깥의 주인이 아니라 gate 안쪽의 의존성이다.
- MCP는 사람의 의사결정 값을 만들어내지 않는다. 성공 기준, 회의록 밖 수치, 정책 확정은 ask-back으로 남긴다.

---

## 4. Runtime Adapter

Workflow layer는 모든 런타임에서 같다. Runtime adapter는 `invoke_runtime_adapter` 한 노드의 구현만 바꾼다.

| runtime | adapter가 바꿀 것 | 바꾸면 안 되는 workflow |
|---|---|---|
| Codex | `.codex/agents/*`, `.codex/skills/using-task-harness/SKILL.md`, Codex sub agent 또는 CLI 호출, `functions.exec_command`/MCP tool envelope, artifact path 수집 방식 | handoff schema, leaf order, L5/L6 guard, route enum, retry counter, partial pass 보존, checkpoint schema |
| Claude | `.claude/agents/ask.md/build.md/review.md`, Task 도구 `subagent_type`, `.claude/hooks/review_gate.py`, `.claude/skills/using-task-harness/SKILL.md`, Claude Code session/hook surface | handoff 한 장 입출력, `fill_handoff -> dispatch -> review_gate -> route`, L1/build/L5/L6 역할, blocked exit 조건 |
| 기타 CLI/새 대화창 | sub agent 생성 명령, stdio/HTTP MCP transport, 파일 위치, skill 설치 경로, timeout/로그 수집 방식 | leaf 1개 = 실행 단위 1개, review gate deterministic slice, ask-back 질문을 추정으로 채우지 않는 원칙 |

### Adapter interface

```yaml
runtime_adapter_request:
  run_id: string
  leaf_id: L1|L3L4|L5|L6
  role: ask|build|review
  handoff_brief_path: path
  evidence_paths:
    - inputs/meeting.md
    - inputs/review-gate.md
  expected_output_path: handoffs/<leaf>.md

runtime_adapter_response:
  status: completed|failed|timeout
  output_path: path
  raw_runtime_ref: string
  error_summary: string|null
```

이 interface 위에서는 Claude와 Codex가 같은 하네스다. 차이는 `raw_runtime_ref`가 Claude Task id인지, Codex sub agent/session id인지, 다른 CLI process id인지뿐이다.

---

## 5. Final Pipeline

- **시작 입력**: `inputs/meeting.md` 원문, leaf graph, handoff template, review gate 규칙, 이전 run-log/checkpoint.
- **실행 graph**: `structured_contract -> state_store -> fill_handoff -> dispatch_plan -> invoke_runtime_adapter -> collect_handoff -> review_gate -> review_parallel -> merge_verdict -> route`.
- **상태 기록**: append-only run event, leaf별 handoff out, verdict, route, failed guard, retry count, pending question, preserved passes, checkpoint id.
- **guard**: format 필수 section, `meeting.md:NN` 줄 실재, 금지값 `60%` 단정 차단, 범위 오염 차단, 이전 pass regression 차단.
- **exit/retry/ask-back**:
  - `exit`: 7칸이 충분하고 L5/L6 guard가 통과했으며 남은 불확실성이 확인 필요 항목으로 분리됨.
  - `retry`: 입력과 기준은 충분하지만 guard 위반이 있음. 실패 사유 한 줄과 return node를 저장하고 실패 leaf만 재실행.
  - `ask-back`: 성공 기준·정책·수치·담당자처럼 사람 결정 없이는 진행 불가. 질문·owner·resume target 저장.
  - `blocked exit`: 같은 retry 사유 2회 반복 또는 외부 회신 없음. last good checkpoint와 pending을 보존.
- **최종 판단자**: MCP는 gate verdict와 상태를 만든다. 최종 채택과 외부 공유 여부는 메인 세션/사람이 닫는다.

---

## 6. MCP Harness Tools 후보

| tool | 입력 | 출력 | 내부 capability |
|---|---|---|---|
| `harness.init_run` | source path, leaf graph, contract path | run id, initial checkpoint | `structured_contract`, `state_store` |
| `harness.fill_handoff` | run id, leaf id | handoff brief path | `structured_output` |
| `harness.dispatch_leaf` | run id, leaf id, runtime | adapter request/response, handoff out path | `skill_dispatch`, `tool_call` |
| `harness.review_gate` | run id, handoff out path, guard profile | deterministic verdict, failed guard list | `guard` |
| `harness.review_parallel` | run id, build artifact, L5/L6 profiles | L5 verdict, L6 verdict, preserved pass map | `evaluation`, `tool_call` |
| `harness.route` | run id, verdict bundle | route, checkpoint, resume target | `route`, `checkpoint`, `targeted_resume` |
| `harness.resume` | run id or checkpoint id | restored state, next node | `state_store`, `targeted_resume` |

최소 구현 순서는 `review_gate -> route -> state_store/checkpoint -> dispatch_leaf`가 맞다. 5-2에서 첫 노드로 좁힌 전환 후보가 review gate 결정론 슬라이스이고, route/state는 그 verdict를 저장해야 자연스럽게 따라붙는다.

---

## 7. 완료 기준 대조

- [x] 4C가 capability로 번역되어 있다: §1에서 Contract/Context/Control/Confidence를 각각 capability node로 매핑했다.
- [x] Capability Graph에 required capability가 보인다: §2 graph와 node map에 `structured_output`, `state_store`, `skill_dispatch`, `tool_call`, `guard`, `evaluation`, `checkpoint`, `targeted_resume`, `route`가 보인다.
- [x] MCP 안쪽 orchestration boundary가 정리되어 있다: §3이 안쪽 node별 강제 항목과 바깥 판단을 분리한다.
- [x] 병렬로 돌릴 node가 MCP 안쪽에 표시되어 있다: §2와 §3의 `review_parallel`이 L5/L6 병렬 검수, 완료 대기, partial pass 보존, 실패 쪽 재검사를 명시한다.
- [x] runtime adapter와 workflow layer가 분리되어 있다: §4에서 Codex/Claude/기타 adapter가 바꾸는 것과 바꾸면 안 되는 workflow를 분리했다.
- [x] 다음 실습에서 실행 데모로 옮길 수 있다: §6이 MCP tool 후보와 최소 구현 순서를 제시한다.
