# 2005-4-3. [최종 프로젝트] 내 업무용 런타임 독립 하네스 실행 데모

## 이 산출물

내 업무용 runtime-independent harness의 실행 데모와 운영 노트. 현재 runtime surface에서 데모할 수 있게 작성하되, 핵심 workflow는 Claude, Codex, OpenCode 등 다른 런타임에도 옮길 수 있도록 capability와 adapter를 분리한다.

## 목적

수강 후 바로 가져갈 최종 결과물을 만든다. 데모는 설명용 산출물이 아니라, 4장에서 만든 `meeting-to-1page` 하네스 v1을 5장의 상태·checkpoint·병렬 검수·route graph로 승격해 **실제로 한 번 실행**해야 한다. 시작 입력, graph 실행, guard 판정, exit/retry/ask-back route, 최종 산출물과 남은 불확실성이 실행 로그로 남아야 한다.

## 이 실습에서 최종으로 만드는 것

최종 산출물은 MCP 서버 하나가 아니라, **4장에서 만든 하네스 v1을 2005-4-2 Capability Graph 그대로 실행 가능하게 만든 하네스 v2**다. MCP는 그 graph 안에서 순서·상태·병렬 검수·route처럼 코드로 잠글 가치가 큰 node를 구현하는 방식이다.

완성물은 다섯 부분으로 나뉜다.

1. **Requirements Contract · 2장 통합**: 2장 인터뷰 하네스 산출물을 직접 실행하지 않고, "reader / decision / success signal / constraints" 계약으로 하네스 v2 안에 통합한다.
2. **Spec Contract · 3장 통합**: 3장 문서형 하네스 산출물을 직접 실행하지 않고, 회의록 → 1page 변환 규칙(고정·남김·질문)으로 v2 안에 통합한다.
3. **Work Execution · 4장**: ask·build·review sub agent로 회의록을 1page 초안까지 끌고 간다.
4. **Review / MCP · 4→5장**: 인용 검사와 사실·범위 검사를 병렬 실행하고 merge한다.
5. **Operation Guide · 5장**: exit/retry/ask-back을 코드로 강제하고, ask-back이면 v2에 통합된 2장 질문 구조로 requirements/spec을 갱신한다.

출발점은 새 예제가 아니라 4장 산출물이다.

| 역할 | 통합 입력 |
|---|---|
| 2장 requirements contract 재료 | `practice-materials/chapter-02/2002-4-3-interview-harness-v1.md`, `2002-2-3-requirements-table.md`, `2002-3-3-one-line-specs.md` |
| 3장 spec contract 재료 | `practice-materials/chapter-03/2003-4-2-document-harness-blueprint.md`, `practice-materials/chapter-03/2003-4-3-workspace/05-2003-4-3-output.md` |
| 3장 session surface 재료 | `practice-materials/chapter-03/2003-4-3-workspace/.claude/hooks/session-start.sh`, `.claude/hooks/check-1page-output.sh`, `.claude/skills/using-1page-harness/SKILL.md`, `.claude/skills/meeting-to-1page/SKILL.md` |
| 5-4-1 Ouroboros 관찰 재료 | `practice-materials/chapter-05/2005-4-1-output-ouroboros-core-logic-analysis.md` |
| v2 생성 위치 | `practice-materials/chapter-05/2005-4-3-workspace/` |
| 4장 Codex workspace 입력 | `practice-materials/chapter-04/codex_workspace_2004-4-3/` |
| 4장 Claude workspace 입력 | `practice-materials/chapter-04/claude_workspace_2004-4-3/` |
| 시작 입력 | `inputs/meeting.md`, `inputs/handoff-template.md`, `inputs/review-gate.md`, `inputs/agent-execution-graph.md` |
| 기존 guard | `.codex/hooks/review_gate.py` 또는 `.claude/hooks/review_gate.py` |
| 기존 상태 | `state/run-log.md`, `state/harness-state.md` |
| 기존 산출물 | `handoffs/handoff-L1-ask.md`, `handoffs/handoff-L3L4-build.md`, `handoffs/handoff-L5-review.md`, `handoffs/handoff-L6-review.md`, `handoffs/final-1page-draft.md` |

따라서 4-3에서 구현해야 하는 기준선은 아래 graph다. node 순서와 입출력 계약은 이 구조를 그대로 따라야 하고, 최소 한 번은 실제 파일을 읽고 쓰며 route까지 완료해야 한다.

먼저 2~5장을 잇는 최상위 graph가 있어야 한다.

```text
requirements_contract [chapter-02 integrated]
  - reader / decision / success signal / constraints를 v2 계약으로 고정한다
  - output: requirements + Define contract
  -> spec_contract [chapter-03]
       - 회의록 -> 1page 7칸 규칙 고정
       - 확인되지 않은 값은 질문으로 남김
       - output: handoff_template + 1page output contract + review_gate profile
  -> session_surface [chapter-03 integrated]
       - SessionStart 안내를 v2 시작 컨텍스트로 주입
       - using-1page-harness 메타 스킬을 runtime guidance로 유지
       - meeting-to-1page 도메인 스킬을 output contract로 유지
       - check-1page-output hook을 review_gate precheck로 흡수
  -> work_execution [chapter-04]
       - ask/build/review leaf 실행
       - output: 1page draft + handoffs + state
  -> review_and_merge [chapter-04 -> 05]
       - review_gate + review_parallel + merge_verdict
  -> operate [chapter-05]
       |- exit: final 1page
       |- retry: checkpoint로 복귀
       |- ask-back: requirements_update -> Define/spec 갱신 후 resume
       `- blocked exit: last_good_checkpoint + pending external
```

그 안쪽에서 MCP/CLI가 실제로 실행할 capability graph는 다음이다.

```text
requirements_contract(reader, decision, success signal, constraints)
  -> spec_contract(1page rules, fixed/remaining/question policy)
  -> session_surface(session-start guidance, meta skill, domain skill, post-write guard)
  -> input(meeting.md, leaf_graph, handoff_template, review_gate)
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
       |- ask-back: requirements_update -> update Define/spec -> resume target
       `- blocked exit: last_good_checkpoint + pending external
```

아래 `review_parallel` MCP는 이 전체 graph 중 검수 node 구현 예시일 뿐이다. 이것만 만들고 끝내면 최종 하네스가 아니다. 최종 데모에서는 `practice-materials/chapter-05/2005-4-3-workspace/`를 새로 만들고, 그 안에서 `requirements_contract -> spec_contract -> session_surface -> structured_contract -> state_store -> fill_handoff -> dispatch_plan -> invoke_runtime_adapter -> collect_handoff -> review_gate -> review_parallel -> merge_verdict -> route`가 2~4장 실제 산출물을 read-only 입력으로 통합해 한 번 실행되어야 한다.

## 권장 시간

45분. 단, 이 시간 안의 목표는 "완벽한 제품화"가 아니라 **실제 실행되는 한 run**이다.

## 준비물

- 2005-4-2 Final Harness Graph
- 5장 새 workspace 경로: `practice-materials/chapter-05/2005-4-3-workspace/`
- 4장 read-only 입력: `codex_workspace_2004-4-3/` 또는 `claude_workspace_2004-4-3/`
- 실제 데모 요청 1개
- 최종 결과를 검토할 사람 또는 역할

## 입력

| 항목 | 내용 |
|---|---|
| 하네스 이름 |  |
| 사용할 업무 상황 |  |
| 사용하지 않을 상황 |  |
| 시작 입력 |  |
| 최종 산출물 |  |
| runtime adapter |  |
| capability graph |  |
| 데모 요청 |  |
| 최종 판단자 |  |

## AI에게 시키기

> `practice-materials/chapter-05/2005-4-3-workspace/`를 새로 만들고, 2장 인터뷰 하네스 산출물과 3장 회의록→1page 명세 산출물, 4장 `meeting-to-1page` 하네스 workspace를 read-only 입력으로 가져와 하네스 v2를 구현해줘. 2장/3장 산출물은 직접 실행하지 말고 requirements contract와 spec contract로 통합해줘. 3장의 SessionStart hook, `using-1page-harness` 메타 스킬, `meeting-to-1page` 도메인 스킬, PostToolUse 검사도 v2의 session surface와 review_gate precheck로 통합해줘. 2005-4-1 Ouroboros 분석에서 배운 frozen contract, append-only state, checkpoint/rewind, evaluation gate, regression guard도 반영해줘. 4장 `inputs/`, `handoffs/`, `state/`, `review_gate.py`는 복사하거나 참조하되 원본을 수정하지 말고, 5장 workspace 안에서 graph node별로 실제 파일을 읽고 쓰게 해줘. `requirements_contract -> spec_contract -> session_surface -> structured_contract -> state_store -> fill_handoff -> dispatch_plan -> invoke_runtime_adapter -> collect_handoff -> review_gate -> review_parallel -> merge_verdict -> route`가 한 번 실행되어 `state/run-log.md`, checkpoint, 최종 route, final 1page 또는 pending external을 남겨야 해. ask-back이면 v2에 통합된 2장 질문 구조로 requirements/spec을 갱신하고 resume target으로 복귀해야 해. 설명용 mock이 아니라 실행 명령과 결과 로그를 포함해줘.

### 바로 작업 지시로 던질 때의 고정 조건

- 작업 위치는 `practice-materials/chapter-05/2005-4-3-workspace/`다. 4장 workspace 안에서 확장하거나 원본을 수정하지 않는다.
- 4장 workspace는 read-only source로만 사용한다. 필요한 파일은 5장 workspace로 복사하거나 상대 경로로 참조한다.
- 새 샘플 프로젝트나 별도 장난감 예제를 만들지 않는다. 5장 workspace는 4장 `meeting-to-1page` 하네스의 v2여야 한다.
- 기존 handoff를 읽어 설명하는 replay만으로 끝내지 않는다. `harness_v2_server.py run ...` 또는 동등한 명령이 실제로 파일을 읽고 쓰며 한 번 실행되어야 한다.
- 2장/3장 산출물은 별도 하네스로 다시 호출하지 않는다. requirements/spec/session_surface 계약으로 v2 내부에 통합한다.
- 4-1 Ouroboros 분석에서 가져올 원칙은 코드나 상태 파일에 보여야 한다: frozen contract, append-only run-log, checkpoint/rewind, evaluation gate, preserved pass regression guard, satisfice exit.
- 완료물에는 실행 명령, 생성/갱신된 파일 목록, run-log 일부, 최종 route, 다음 resume target이 포함되어야 한다.

## 작성 템플릿

````markdown
# Runtime-independent Harness Demo — <하네스 이름>

## 1. Harness Overview
| 항목 | 내용 |
|---|---|
| 해결하려는 실패 |  |
| 사용할 상황 |  |
| 사용하지 않을 상황 |  |
| 최종 산출물 |  |
| 최종 판단자 |  |

## 2. Workflow Layer
```text
requirements_contract
  -> spec_contract
  -> session_surface
  -> structured_contract
  -> state_store.load_or_init
  -> fill_handoff
  -> dispatch_plan
  -> invoke_runtime_adapter
  -> collect_handoff
  -> review_gate
  -> review_parallel( L5 citation ∥ L6 domain/scope/FN )
  -> merge_verdict
  -> route( exit/retry/ask-back/blocked exit )
```

## 3. Runtime Adapter
| runtime | 실행 surface | adapter가 책임질 것 |
|---|---|---|
| Codex |  |  |
| Claude |  |  |
| 기타 |  |  |

## 4. Demo Run
| node | input | output | verdict |
|---|---|---|---|
| requirements_contract |  |  |  |
| spec_contract |  |  |  |
| session_surface |  |  |  |
| structured_contract |  |  |  |
| state_store.load_or_init |  |  |  |
| fill_handoff |  |  |  |
| dispatch_plan |  |  |  |
| invoke_runtime_adapter |  |  |  |
| collect_handoff |  |  |  |
| review_gate |  |  |  |
| review_parallel (L5 ∥ L6) |  |  |  |
| merge_verdict |  |  |  |
| route |  |  |  |

## 5. Final Result
- 산출물:
- 판단 근거:
- 남은 불확실성:
- route:
- 다음 행동:

## 6. Operating Notes
| 상황 | 해야 할 일 | 하지 말아야 할 일 |
|---|---|---|
| 새 입력 |  |  |
| guard 실패 |  |  |
| retry 반복 |  |  |
| ask-back: requirements/spec 갱신 |  |  |
| 최종 판단 |  |  |

## 7. Next Iteration
- capability가 부족해 adapter로 우회한 지점:
- MCP로 코드화할 다음 node:
- 사람에게 남겨야 하는 판단:
````

## 실제 구현 산출물

하네스 v2는 다음 파일 또는 이에 준하는 실행 산출물을 남겨야 한다.

기본 폴더 구조는 다음을 기준으로 한다.

```text
practice-materials/chapter-05/2005-4-3-workspace/
├── harness_v2_server.py          # graph 엔진 + CLI (run / resume / review)
├── review_server.py              # MCP node 서버: review_parallel (Claude Agent SDK ∥ codex exec)
├── harness_mcp_server.py         # MCP graph 서버: graph 전체를 run/resume/run_full 툴로 노출
├── mcp_client_demo.py            # 등록된 MCP 서버를 직접 spawn해 tool 호출하는 재현 데모
├── setup-readonly-sources.sh     # (선택) 2~4장 원본 복사. workspace는 이미 self-contained
├── .mcp.json                     # ★ 워크스페이스 루트(.claude/ 안 아님) — 두 MCP 서버 정의
├── .claude/
│   ├── settings.json             # SessionStart/PostToolUse hook + enabledMcpjsonServers(두 서버 활성화)
│   ├── commands/                 # 슬래시 커맨드
│   │   ├── run-1page.md
│   │   └── resume-1page.md
│   ├── agents/{ask,build,review}.md
│   ├── hooks/{session-start.sh, check-1page-output.sh}   # check-1page-output → review_gate.py 위임
│   └── skills/{using-1page-harness, meeting-to-1page}/SKILL.md
├── .codex/
│   ├── config.toml               # [features] hooks + [mcp_servers] 템플릿
│   ├── hooks.json                # SessionStart + PostToolUse(review_gate precheck)
│   ├── agents/{ask,build,review}.toml
│   ├── hooks/{session_start.py, review_gate.py}          # review_gate.py = 4장 원본 그대로 복사
│   └── skills/using-task-harness/SKILL.md
├── contracts/                    # ★ frozen 계약
│   ├── requirements-contract.md  # 2장 통합 (reader/decision/success/constraints + 사각지대 두 질문)
│   └── spec-contract.md          # 3장 통합 (7칸 output contract + 고정·남김·질문 + review_gate profile)
├── inputs/                       # read-only 시작 입력 + answers.md(ask-back 회신)
│   ├── meeting.md, handoff-template.md, review-gate.md, agent-execution-graph.md
│   ├── handoff-L3-result.md      # 성공 기준 '모호' 상태 build (ask-back 유발 입력)
│   └── answers.md                # 2장 채택(☑/☒) + acceptance.metric 회신
├── source/                       # ★ 4장 실제 산출 (replay 어댑터 소스 뱅크, read-only)
│   ├── handoffs/{handoff-L1-ask, handoff-L3L4-build, ...}.md
│   └── state/...
├── handoffs/                     # v2가 생성: L1/build/L5/L6 handoff + final-1page-draft
└── state/                        # run-log.md(append-only) + run-log.jsonl + checkpoints/ + pending-external
    └── checkpoints/              # route.json(회전 .1/.2) + last_good.json + structured-contract/state-store
```

| 산출물 | 역할 |
|---|---|
| `practice-materials/chapter-05/2005-4-3-workspace/` | 5장 최종 하네스 v2 workspace 루트다. |
| `harness_v2_server.py` 또는 동등한 MCP/CLI 실행 파일 | 5장 workspace 안에서 graph node를 실제 함수로 실행한다. |
| `../../../../.claude` | Claude runtime surface다. 3장의 SessionStart hook, PostToolUse guard, `using-1page-harness`, `meeting-to-1page` 구조를 계승한다. |
| `../../../../.codex` | Codex runtime surface다. Codex agents, hooks, skills, CLI adapter 규약을 둔다. |
| `.claude/skills/using-1page-harness/SKILL.md` | 3장 메타 스킬 주입 구조를 Claude 런타임에 맞게 옮긴다. |
| `.claude/skills/meeting-to-1page/SKILL.md` | 3장 도메인 스킬의 7칸 output contract와 보존 표현 규칙을 옮긴다. |
| `.claude/hooks/session-start.sh` 또는 동등한 hook | 3장의 SessionStart 규약처럼 시작 시 현재 contract, 금지선, route 규칙을 주입한다. |
| `.claude/hooks/check-1page-output.sh` 또는 동등한 precheck | 3장의 PostToolUse guard를 v2 review_gate precheck로 흡수한다. |
| `.codex/skills/using-task-harness/SKILL.md` 또는 동등한 meta skill guidance | 같은 meta workflow를 Codex runtime guidance로 옮긴다. |
| `state/checkpoints/*.json` | `state_store.load_or_init`, `route`, `targeted_resume`가 읽고 쓰는 checkpoint다. |
| `state/run-log.md` 또는 `state/run-log.jsonl` | node별 input/output/verdict/route append-only 로그다. |
| `handoffs/*.md` | 4장 handoff contract를 유지한 실제 sub agent 입출력이다. |
| `handoffs/final-1page-draft.md` 또는 `state/pending-external.md` | 최종 exit 결과 또는 ask-back/blocked exit 결과다. |

## 2005-4-1 Ouroboros 분석에서 가져올 구현 원칙

4-1 분석은 복제 대상이 아니라, 하네스 v2에 넣을 실행 원칙의 근거다. 최소한 아래 다섯 가지가 구현에 보여야 한다.

| Ouroboros에서 본 것 | v2에 통합할 것 |
|---|---|
| `Seed`가 frozen이고 direction을 실행 중 바꾸지 않음 | 2장 requirements와 3장 spec을 `requirements_contract` / `spec_contract`로 잠그고, leaf 실행 중 임의 변경을 막는다. |
| `EventStore` append-only + replay | `state/run-log.md` 또는 `state/run-log.jsonl`을 append-only로 남기고, `state_store.load_or_init`이 replay로 현재 상태를 복원한다. |
| `CheckpointStore`와 rewind 기록 | input/spec/execution/review checkpoint를 별도 저장하고, retry/ask-back 때 return node와 reason을 남긴다. |
| `EvaluationPipeline`이 mechanical → semantic → consensus 순서로 조기 차단 | `review_gate` 결정론 검사(format/line/60%)가 실패하면 L5/L6 의미 검수로 넘기지 않는다. 통과 후에만 `review_parallel`을 돈다. |
| `RegressionDetector`가 이전 통과 AC 재실패를 막음 | `merge_verdict`가 preserved pass를 저장하고, retry 후 이전 pass가 깨지면 regression으로 route한다. |
| `ConvergenceCriteria`가 모든 gate 통과 후에만 멈춤 | exit은 7칸 충분 + guard 통과 + 불확실성 분리일 때만 허용한다. "더 좋아질 수 있음"은 retry 사유가 아니다. |

최소 실행 명령 예시는 다음 모양이어야 한다. (실측 정정: CLI는 `run` / `resume` / `review` 3개 subcommand다. `--resume-or-init` 같은 플래그는 없다. 자세한 시퀀스·플래그 표는 아래 §실전 구현 참고.)

```bash
# workspace 안에서 실행. ① fresh run → 성공 기준 metric 미확정이라 ask-back, pending external 남김
python3 harness_v2_server.py run --review-adapter local

# ② 회신(2장 채택)을 반영해 이어 돌림 → requirements_update → 검수 → exit
python3 harness_v2_server.py resume --answers inputs/answers.md --review-adapter local
```

실행이 끝나면 다음 검증 명령이 통과해야 한다.

```bash
python3 .codex/hooks/review_gate.py handoffs/handoff-L5-review.md --leaf L5
python3 .codex/hooks/review_gate.py handoffs/handoff-L6-review.md --leaf L6
```

그리고 run-log에는 최소한 다음 항목이 있어야 한다.

```text
requirements_contract: pass|ask-back
spec_contract: pass
session_surface: injected
structured_contract: pass
state_store.load_or_init: pass
fill_handoff: pass
dispatch_plan: pass
invoke_runtime_adapter: completed
collect_handoff: pass
review_gate: pass|rework|ask-back
review_parallel: L5=<verdict>, L6=<verdict>
merge_verdict: preserved_passes=<...>, failed_sides=<...>
route: exit|retry|ask-back(requirements_update)|blocked exit
```

## 코드화할 node 예시 — 병렬 검수 MCP (Claude ∥ Codex)

이 섹션은 최종 하네스 전체를 대표하는 **검수 node 예시**를 MCP로 코드화하는 부분이다. 회의록 1page 초안이 나오면 인용 출처 검사는 **Claude**로, 사실·범위 검사는 **Codex**로 동시에 돌리고 결과를 합친다(5-1에서 1순위로 고른 `parallel-review-merge`). 두 검사는 같은 인터페이스(프롬프트 → 판정)를 쓰고 런타임만 다르다 — 5-2의 런타임 독립이 코드로 실현되는 지점이다. 바깥에서 보면 `review_parallel` 콜 하나, 안에서는 Claude·Codex 두 세션이 병렬로 돈다. 이 node는 위 실제 실행 루프 안에서 호출되어야 하며, 독립 예제 파일로만 남기면 실패다.

즉, 이 MCP 서버가 맡는 범위는 다음으로 제한한다.

- 맡는 것: 병렬 검수 실행, 결과 merge, partial pass 보존, retry route 반환
- 맡지 않는 것: 문제 정의, 1page 작성 규칙 전체, 최종 채택 판단, ask-back 질문의 답 추정

> 이 구조는 2005-4-1에서 읽은 Ouroboros `providers/`(같은 `complete()` 인터페이스, 어댑터만 다름)를 그대로 옮긴 것이다.
>
> ⚠️ **정정(실측 반영)**: 아래 최소판 스켈레톤은 `run_on_claude`를 Anthropic 메시지 API(`AsyncAnthropic`, `ANTHROPIC_API_KEY` 필요)로 보여주지만, 실제 구현은 **Claude Agent SDK**(`from claude_agent_sdk import query, ClaudeAgentOptions`)를 쓴다. 이유: Claude Agent SDK는 `claude` CLI를 감싸 **Claude Code 기존 인증을 그대로 재사용**하므로 raw API 키가 필요 없다(Ouroboros `claude_code_adapter.py`와 같은 런타임). API 키 없는 수강생 환경에서는 이쪽이 정답이다. 정확한 코드·준비물·중첩 우회는 아래 **§실전 구현 — 이 자료 하나로 작동**에 있다.
>
> 준비물(분기):
> - **local/replay 경로**(기본·결정론·재현): 추가 패키지·CLI·API 키 **불필요**. `python3 harness_v2_server.py run`이 바로 돈다.
> - **live/mcp/work=claude 경로**(실제 런타임): `pip install mcp claude-agent-sdk anyio` + PATH의 `claude`·`codex` CLI 로그인. (Anthropic API 키는 안 쓴다.)

### AI에게 시키기

> 회의록 1page 검수용 MCP 서버를 만들어줘. 인용 출처 검사는 Anthropic 메시지 API(Claude)로, 사실·범위 검사는 `codex exec`(Codex)로 `asyncio.gather` 병렬 실행하고 merge해줘. 한쪽만 통과하면 통과 결과는 보존하고 실패한 검사만 재작업 대상으로 표시해줘. `review_parallel(draft, source)` 도구 하나, FastMCP + 외부 패키지 최소로.

### 스켈레톤 (개념 설명용 — 실행용 전문은 §실전 구현 §1 / 동봉 `review_server.py`)

> ⚠️ 아래는 "Claude는 어떤 인터페이스, Codex는 어떤 인터페이스"를 보여주는 **개념용**이다. 이 블록을 그대로 `review_server.py`로 저장하면 module-level `claude = AsyncAnthropic()`가 `ANTHROPIC_API_KEY`를 요구해 키 없는 환경에서 import 단계부터 죽는다. **실제 동봉 `review_server.py`는 `anthropic`을 전혀 import하지 않고 `claude_agent_sdk`만 `run_on_claude` 안에서 lazy import**한다(키 불필요, §1 코드 참조). 바로 돌릴 거면 동봉본 또는 §1의 SDK `run_on_claude`로 교체한다.

```python
# review_server.py — 회의록 1page 검수 MCP (Claude ∥ Codex)
import asyncio
import json
import os
import tempfile
from pathlib import Path

from anthropic import AsyncAnthropic          # pip install anthropic
from mcp.server.fastmcp import FastMCP          # pip install mcp

mcp = FastMCP("meeting-1pager-review")
claude = AsyncAnthropic()                       # ANTHROPIC_API_KEY 환경변수 사용

# 어댑터 둘: 같은 (prompt) -> text 모양, 런타임만 다르다 (5-2 런타임 독립)
async def run_on_claude(prompt: str) -> str:
    msg = await claude.messages.create(
        model="claude-sonnet-4-6",
        max_tokens=1024,
        messages=[{"role": "user", "content": prompt}],
    )
    # 텍스트 블록을 이어붙인다 (Anthropic 응답 파싱 방식)
    return "".join(b.text for b in msg.content if b.type == "text")

async def run_on_codex(prompt: str) -> str:
    # Codex CLI 비대화형: 프롬프트는 stdin, 최종 답은 --output-last-message 파일로
    fd, out_path = tempfile.mkstemp(suffix=".txt")
    os.close(fd)
    try:
        proc = await asyncio.create_subprocess_exec(
            "codex", "exec", "--json", "--skip-git-repo-check", "--output-last-message", out_path,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        await proc.communicate(prompt.encode("utf-8"))          # 프롬프트 → stdin
        return Path(out_path).read_text(encoding="utf-8").strip()  # 최종 메시지 ← 파일
    finally:
        os.unlink(out_path)

def as_verdict(text: str) -> dict:
    start, end = text.find("{"), text.rfind("}")               # 펜스/설명을 걷어내고 JSON만
    try:
        return json.loads(text[start:end + 1])                 # {"verdict": "pass"/"fail", "violations": [...]}
    except (ValueError, json.JSONDecodeError):
        return {"verdict": "error", "raw": text}

async def run_review(runner, instruction, draft, source):
    prompt = (
        instruction + "\n\n[1page]\n" + draft + "\n\n[회의록 원문]\n" + source + "\n\n"
        + '결과는 {"verdict": "pass" 또는 "fail", "violations": [...]} JSON으로만 답해줘.'
    )
    return as_verdict(await runner(prompt))

async def check_citation(draft, source):        # 인용 출처 검사 → Claude
    return await run_review(run_on_claude, "1page의 인용이 회의록 원문 줄과 맞는지만 검사해줘", draft, source)

async def check_facts(draft, source):           # 사실·범위 검사 → Codex
    return await run_review(run_on_codex, "외부 사실 단정·범위 밖 항목·미확인 수치만 검사해줘", draft, source)

@mcp.tool()
async def review_parallel(draft: str, source: str) -> dict:
    # Claude·Codex 두 런타임 세션을 동시에 실행
    citation, facts = await asyncio.gather(
        check_citation(draft, source),
        check_facts(draft, source),
    )
    failed = [r for r in (citation, facts) if r.get("verdict") != "pass"]
    return {
        "passed": [r for r in (citation, facts) if r.get("verdict") == "pass"],
        "failed": failed,
        "route": "exit" if not failed else "retry",   # 한쪽만 실패면 통과 결과 보존
    }

if __name__ == "__main__":
    mcp.run()
```

핵심은 `run_on_claude`·`run_on_codex`가 같은 `(prompt) -> text` 모양을 쓰고 런타임만 다르다는 점이다. Claude는 Anthropic 메시지 API로 텍스트 블록을 이어붙여 받고, Codex는 `codex exec`에 프롬프트를 stdin으로 넣고 `--output-last-message` 파일에서 최종 답을 읽는다 — Ouroboros `providers/anthropic_adapter.py`·`codex_cli_adapter.py`가 실제로 쓰는 방식 그대로다(Codex는 stdout이 진행 로그라 파일로 받아야 정확하다). `asyncio.gather`가 둘을 동시에 돌리고, merge가 한쪽 실패 시 통과 결과를 보존한다. Ouroboros는 이 둘을 `LLMAdapter.complete()` 하나로 합쳐 `providers/`에 두었고(2005-4-1에서 본 구조), `--json`·`--output-schema`로 JSONL 이벤트 파싱과 스키마 강제까지 더한다.

### 데모에서 보여줄 것

- 2장 인터뷰 하네스 산출물이 reader / decision / success signal / constraints를 만든 입력으로 들어가는 것을 보여준다.
- 3장 회의록→1page 명세가 fixed / remaining / question policy와 7칸 output contract로 잠기는 것을 보여준다.
- 3장 SessionStart hook과 메타 스킬 주입 구조가 v2의 session surface로 통합된 것을 보여준다.
- 5장 `2005-4-3-workspace`에서 `harness_v2_server.py run ...` 또는 동등한 실행 명령을 한 번 돌린다.
- `structured_contract`가 기존 handoff schema와 1page output contract를 잠그는 것을 보여준다.
- `state_store.load_or_init`이 기존 `state/run-log.md`와 checkpoint를 읽거나 초기화하는 것을 보여준다.
- `fill_handoff -> dispatch_plan -> invoke_runtime_adapter -> collect_handoff`가 실제 handoff 파일을 만들거나 회수하는 것을 보여준다.
- 기존 4장 `review_gate.py`가 format, `meeting.md:NN` 줄 실재, `60%` 단정 금지 검사를 실제로 수행하는 것을 보여준다.
- `review_parallel`에서 L5/L6 검수가 병렬로 돌고, 한쪽 실패 시 통과 결과가 보존되는 것을 보여준다.
- `merge_verdict -> route`가 exit / retry / ask-back / blocked exit 중 하나를 내고, ask-back이면 v2에 통합된 requirements 질문 구조로 Define/spec 갱신 checkpoint를 남기는 것까지 보여준다.

## 실전 구현 — 이 자료 하나로 작동 (실측 반영)

앞 절들은 개념과 graph 골격이다. 이 절은 실제로 돌려 본 구현에서 빠지면 막히는 조각을 모았다. 여기까지 따르면 이 자료만으로 workspace를 재현하고 `run → ask-back → resume → exit`까지 돌릴 수 있다.

### 0. 준비물 — 경로별 분기

| 경로 | 필요한 것 | 성격 |
|---|---|---|
| `local` / `replay` (기본) | 추가 패키지·CLI·API 키 **없음**. Python 3.11+ | 결정론·재현. 같은 입력 → 같은 route |
| `live` / `mcp` / `work=claude` | `pip install mcp claude-agent-sdk anyio` + PATH의 `claude`·`codex` CLI 로그인 | 실제 런타임. **비결정적** |

- 이 workspace는 self-contained다(`inputs/`·`source/`·`contracts/`·`.codex/hooks/review_gate.py` 포함). `setup-readonly-sources.sh`는 2~4장 원본을 가진 저자 환경에서만 쓴다. 원본이 없으면 스크립트가 친절히 skip한다.
- `run`은 기본 `--fresh`라 매 run마다 `handoffs/`·`state/`를 초기화한다. append-only 연속을 보려면 `run --keep` 또는 `run → resume` 순서로 돌린다.

### 1. review는 Claude Agent SDK로 — raw API 아님, 키 불필요

가이드 위쪽 스켈레톤의 `run_on_claude`(Anthropic 메시지 API)는 `ANTHROPIC_API_KEY`가 필요하다. 실제 구현은 **Claude Agent SDK**를 써서 `claude` CLI를 감싸고 **Claude Code 기존 인증을 그대로 재사용**한다(Ouroboros `claude_code_adapter.py`와 같은 런타임). 키가 필요 없다.

```python
async def run_on_claude(prompt: str) -> str:
    from claude_agent_sdk import query, ClaudeAgentOptions, AssistantMessage, TextBlock
    options = ClaudeAgentOptions(
        system_prompt="너는 회의록 1page 인용 검사기다. JSON으로만 답한다.",
        allowed_tools=[], max_turns=1,
        model="claude-haiku-4-5-20251001",   # REVIEW_MODEL env로 교체 가능
        env={"CLAUDECODE": ""},              # ★ 아래 설명
    )
    parts = []
    async for msg in query(prompt=prompt, options=options):
        if isinstance(msg, AssistantMessage):
            parts.extend(b.text for b in msg.content if isinstance(b, TextBlock))
    return "".join(parts)
```

- ★ **CLAUDECODE 중첩 우회**: Claude Code 세션 안에서 SDK가 `claude` CLI를 다시 띄우면 "cannot be launched inside another Claude Code session"으로 죽는다. `ClaudeAgentOptions(env={"CLAUDECODE": ""})`로 우회한다. SDK는 `{**os.environ, **options.env}`로 병합하므로 키를 **빈 값으로 덮어써야** 한다(없애기 X — os.environ에서 다시 들어옴).
- Codex는 `codex exec --json --skip-git-repo-check --output-last-message <파일>`(프롬프트는 stdin). `returncode != 0`(미로그인 등)이면 `{"verdict":"error", ...}`를 돌려준다.
- **review_adapter 세 값**: `local`(외부 호출 없는 결정론 judge) / `live`(in-process Claude∥Codex) / `mcp`(등록된 review_server.py를 stdio MCP 프로토콜로 호출).
- **비결정성·폴백**: live/mcp는 LLM verdict에 의존해 매번 같은 route를 보장하지 않는다. 패키지·런타임·인증이 불가하거나 verdict가 `error`면 **local judge로 정직하게 폴백**한다(로그 `review.parallel.fallback`). 인증 실패가 빈-violation '재작업'으로 위장되지 않게 한다.

### 2. work_adapter — 실제 sub agent 디스패치 (replay vs claude)

`invoke_runtime_adapter`의 work leaf(ask/build)는 두 방식 중 하나로 돈다.

| work_adapter | ask/build 생성 방식 |
|---|---|
| `replay` (기본) | 4장 실제 산출(`source/handoffs/`)을 contract 상태에 맞게 회수. 결정론 |
| `claude` | `.claude/agents/{ask,build}.md` frontmatter를 떼고 본문을 system_prompt로, 줄번호 회의록+골격을 user_prompt로 실제 Claude Agent SDK sub agent를 띄워 생성 |

- claude 경로는 디스패치 직후 `review_gate.py` precheck로 검증한다(`subagent.ask.dispatched`/`subagent.build.dispatched`). build이 precheck를 못 넘기면 1회 재디스패치한다.
- ★ claude 디스패치가 실패하면(모델 접근 불가·SDK 오류) **replay로 폴백**한다(review live와 대칭). graph가 unhandled traceback으로 죽지 않는다. 모델은 `WORK_MODEL` env로 교체.

### 3. review_gate.py — 두 subprocess 모드 + judge 정렬 제약

4장 `review_gate.py`(원본 그대로 복사, 미수정)를 graph 안에서 **두 모드**로 호출한다.

- **PostToolUse 모드**(stdin JSON `{"tool_input":{"file_path":...}}`): `handoffs/*.md`의 format·`meeting.md:NN` 줄 실재·60% 단정을 결정론 precheck. rc 0(통과)/2(위반).
- **`--leaf L1|L5|L6` 모드**: leaf gate. stdout `route: <pass|rework|ask-back|exit>` + exit 0/1.

★ **핵심 제약 — local judge는 review_gate.py보다 엄격하면 안 된다.** `judge_L6_domain`의 60% 표지 집합(`_UNCERTAINTY_MARKERS`)은 `review_gate.py`의 `QUALIFIERS`와 **같은 10개**여야 한다. 범위 밖 항목도 "범위 밖" 마커가 있으면 통과시켜야 한다. judge가 gate보다 좁으면, gate(precheck)는 통과시키는데 judge만 막아 ask-back 판정이 어긋난다(실제로 두 번 물렸다 — "남은 질문: 60% 출처" 줄과 "※ 범위 밖" 부기). `judge_L5_citation`도 줄 실재·인용 존재만 hard 검사하고, canonical 핵심 줄(`{5,10,12,22,23}`, 출처 `inputs/review-gate.md` Evidence Matrix) 누락은 정보성 note로만 둔다(gate가 특정 줄을 강제하지 않으므로).

★ **이중 검증 구조**: `review_parallel`의 judge는 `handoffs/handoff-L3L4-build.md`(build handoff)를 입력으로 보고, 그 결과로 `handoffs/handoff-L5-review.md`·`L6-review.md`를 따로 쓴 뒤, `gate_cli --leaf`로 그 **review handoff**를 재검증한다. 즉 judge와 gate가 보는 파일이 다르다 — "gate가 judge 결과를 재검증한다"고 오해하지 않게.

### 4. retry 루프 + satisfice exit (코드로 강제)

- `route=retry`면 `fill_handoff`부터 재실행(재디스패치)한다. `max_retries` 안전밸브 + route 노드가 같은 사유 2회면 `blocked exit`를 내 무한루프를 막는다(Ouroboros `_execute_phase_with_retry` + safety valve).
- **satisfice exit은 `final_1page_check`(보존표현 큰따옴표·확인필요항목 섹션·60% 본문 누출) 통과일 때만.** 위반이면 exit하지 않고 retry/blocked로 보낸다. "guard 통과일 때만 exit" 원칙을 코드로 잠근다.

### 5. 계약 통합(2장·3장) + ask-back→resume용 answers.md

- `contracts/requirements-contract.md`: 2장 Seed(goal/constraints/acceptance) + **사각지대 두 질문**(놓친 시각 3 / 잘려나간 가능성 3)을 통합. `acceptance.metric`은 "미확정"으로 둔다.
- `contracts/spec-contract.md`: 3장 7칸 output contract + 고정/남김/질문 + 보존 표현 + review_gate profile.
- ask-back을 닫으려면 `inputs/answers.md`(2002-3-3 evolve-step의 ☑/☒ 채택 형식 + `acceptance.metric: "..."` 줄)가 필수다. 형식:

```markdown
## pending question id
requirements.acceptance.metric
## 채택 (Q1 놓친 시각 / Q2 잘려나간 가능성)
- ☑ (d) 성공 기준을 측정 지표로 본다
- ☑ (e) 측정 단위 = 문의 비율, 기간 = 다음 회의까지 4주, 목표값 = 15% 감소
- ☒ (f) 기준선(CS 60% 추정)은 출처 미확인이라 지표에 쓰지 않는다
## 갱신 결과
- acceptance.metric: "다음 회의까지 4주 동안 신청 직후 환불·일정 문의 비율을 15%로 감소"
```

`apply_answers`가 정규식으로 `☑`와 `acceptance.metric:` 줄을 읽는다. **채택(☑)이 없으면 갱신을 멈추고 `blocked exit`**(자동 추측 금지). 채택이 있으면 requirements/spec을 갱신하고 **v2로 re-freeze**(SHA-256 갱신)한 뒤 `resume_target`(fill_handoff)부터 이어 돈다.

### 6. 두 층위 MCP + 등록

| 서버 | 노출 범위 | 메인 세션이 콜하면 |
|---|---|---|
| `review_server.py` (`meeting-1pager-review`) | graph 중 **review_parallel 한 node** | 검수만 (Claude 인용 ∥ Codex 사실·범위) |
| `harness_mcp_server.py` (`meeting-to-1page-harness`) | **capability graph 전체** — 툴 3개: `run_meeting_to_1page` / `resume_meeting_to_1page` / `run_full_meeting_to_1page` (CLI subcommand명 run/resume과 헷갈리지 말 것) | 잠긴 순서로 graph 한 번에 → route 반환 |

- graph 서버는 동기 graph를 `anyio.to_thread.run_sync(traverse)`로 감싸 내부 `asyncio.run` 충돌을 피한다.
- ★ **structuredContent**: FastMCP 툴 반환을 `-> dict[str, Any]`로 주석해야 output_schema가 생겨 `structuredContent`가 채워진다. bare `-> dict`면 None이라 텍스트 블록 JSON 폴백으로만 파싱된다.

**등록 위치(실측 — 도구가 실제로 읽는 곳):**

| 런타임 | 정의 위치 | 활성화 |
|---|---|---|
| Claude Code | 워크스페이스 **루트 `../../../../.mcp.json`** (`../../../../.claude` 안이 아님 — Claude는 `../../../../.claude`에서 MCP 정의를 안 읽음). 상대경로 `review_server.py` 권장하되 **반드시 workspace 루트에서 `claude`를 실행**해야 cwd가 맞아 서버가 spawn된다(루트 밖에서 띄우면 파일을 못 찾아 죽음). 경로 고정이 필요하면 `claude mcp add -s project ... -- python3 "$PWD/review_server.py"`로 절대경로 등록 | `../../../../.claude/settings.json`의 `"enabledMcpjsonServers": ["meeting-1pager-review","meeting-to-1page-harness"]` |
| Codex | 전역 `~/.codex/config.toml` (프로젝트-로컬 `.codex/config.toml` mcp_servers는 자동 로드 안 됨) | `codex mcp add meeting-1pager-review -- python3 "$PWD/review_server.py"` |

```bash
claude mcp get meeting-to-1page-harness          # Status: ✓ Connected
# 메인 세션이 graph 전체를 MCP 한 콜로:
claude -p "mcp__meeting-to-1page-harness__run_meeting_to_1page 툴을 work_adapter=replay review_adapter=local로 호출하고 route만 답해라" \
  --allowedTools "mcp__meeting-to-1page-harness__run_meeting_to_1page" --mcp-config .mcp.json --strict-mcp-config
# 등록된 서버를 직접 spawn해 tool 호출하는 재현 데모: python3 mcp_client_demo.py
```

> 툴 fully-qualified 이름: `mcp__<서버명>__<툴명>`. mcp_client_demo 전에 먼저 `resume`로 metric-confirmed exit 산출물(`handoffs/handoff-L3L4-build.md`)을 만들어 두면 live 검수 false-fail이 준다.

### 7. 슬래시 커맨드

`.claude/commands/run-1page.md`·`resume-1page.md`:

```markdown
---
description: 회의록→1page 하네스 graph를 한 번 실행 (graph-level MCP 툴 호출)
argument-hint: "[work_adapter] [review_adapter]"
allowed-tools: mcp__meeting-to-1page-harness__run_meeting_to_1page, Bash(python3:*), Read
---
mcp__meeting-to-1page-harness__run_meeting_to_1page 를 $ARGUMENTS 인자로 호출하고, route로 분기해
(ask-back→pending 요약 후 /resume-1page 안내 / exit→7칸 / retry·blocked→사유) 보고한다.
MCP 서버가 안 보이면 fallback: python3 harness_v2_server.py run --work-adapter <w> --review-adapter <r>.
```

### 8. 실행 시퀀스 + 플래그 표

CLI는 `run` / `resume` / `review` 3개 subcommand다(`--resume-or-init` 같은 플래그는 없다).

| subcommand | 동작 |
|---|---|
| `run [--fresh]` | fresh init. metric 미확정 → ask-back, `state/pending-external.md` |
| `resume --answers inputs/answers.md` | requirements_update → 재검수 → exit |
| `review [--build <path>]` | review_parallel node 단독 시연 (partial-pass 보존) |

| 플래그 | 값 | 의미 |
|---|---|---|
| `--review-adapter` | `local`(기본) / `live` / `mcp` | 검수 런타임 |
| `--work-adapter` | `replay`(기본) / `claude` | ask/build 실제 sub agent 디스패치 |

결정론 재현(같은 입력 → 같은 route)이 필요하면 모든 단계를 `--review-adapter local --work-adapter replay`로 돌린다.

### 부록 — 정직한 한계 (수강생에게 그대로 알린다)

- **L6 범위밖 마커 검사는 결정론 휴리스틱**이다. 셀에 "범위 밖" 마커를 끼워 넣으면 forbidden 항목이 통과할 수 있다(`review_gate.py`도 같은 한계). 역할 분리로 봐야 한다: 성공 기준 metric 미확정 같은 **구조적 ask-back은 live/mcp에서도 결정론 게이트**(local L6 == 재질문 override + `gate --leaf L6`)가 강제한다 — LLM이 끄지 못한다. LLM 검수(live/mcp)는 그 위에서 마커-부기 우회 같은 **비구조적 의미 위반을 추가로** 잡는 층이다.
- `review_gate.py`의 `review()`(--leaf 경로) 60% 검사는 표지 2개만 보고 PostToolUse 모드(10개)보다 좁다. 이는 4장 원본을 **그대로 유지(미수정)**한 결과다 — "원본 미수정" 원칙을 지킨 의도된 비대칭.
- **checkpoint는 무결성 해시(SHA-256)를 가진 보조 채널**이고, 실제 resume 진실원천은 `state/run-state.json`이다. `state_store`가 last_good 해시를 검증하고 빠진 값만 보강한다.
- Codex/Claude **agent 미러는 같은 입력 계약**을 가리켜야 한다. 보존 표현은 `spec-contract.md §5`로 통합됐다(v2에 L2 node 없음) — `.codex/agents/build.toml`의 입력 항목도 거기를 가리킨다.

## 완료 기준

- MCP 서버만 제출하지 않는다. 전체 workflow, adapter, 운영 기준이 함께 있어야 한다.
- 설명용 mock이나 표 채우기만 제출하지 않는다. 5장 workspace에서 4장 실제 파일을 read-only 입력으로 한 run이 있어야 한다.
- 2005-4-2 Capability Graph의 node 순서가 데모 run에 그대로 보인다.
- `requirements_contract -> spec_contract -> session_surface -> structured_contract -> state_store -> fill_handoff -> dispatch_plan -> invoke_runtime_adapter -> collect_handoff -> review_gate -> review_parallel -> merge_verdict -> route`가 한 번 이어진다.
- 3장의 `SessionStart` / meta skill / domain skill / PostToolUse guard 구조가 v2의 시작 컨텍스트, runtime guidance, output contract, deterministic precheck로 각각 통합되어 있다.
- `state/run-log.md` 또는 `state/run-log.jsonl`에 node별 실행 결과가 남아 있다.
- checkpoint가 저장되고, retry/ask-back이면 resume target이 남아 있다. ask-back resume target은 통합된 requirements question set과 갱신된 spec으로 연결되어야 한다.
- 기존 4장 `review_gate.py`의 format, `meeting.md:NN` 줄 실재, `60%` 단정 금지 검사가 실제로 호출된다.
- workflow layer와 runtime adapter가 분리되어 있다.
- 현재 runtime surface에서 운영할 방식이 적혀 있다.
- 데모 run에 node별 input/output/verdict가 있다.
- `review_parallel` MCP가 인용·사실 검사를 병렬로 돌려 merge하고, 한쪽 실패 시 통과 결과를 보존한다.
- 최종 route가 exit/retry/ask-back/blocked exit 중 하나로 나온다.
- 다른 런타임으로 옮길 때 유지할 workflow와 바꿀 adapter가 구분되어 있다.
