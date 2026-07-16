# Runtime-independent Harness Demo — meeting-to-1page Harness v2

> 4장 `meeting-to-1page` 하네스 v1을 5장 capability graph로 승격한 v2. 실제로 한 번 실행한 데모다.
> 실행 위치: `practice-materials/chapter-05/2005-4-3-workspace/`. 4장 codex workspace는 read-only 입력.
> 이 문서의 수치·verdict·route는 모두 아래 실행 명령의 실제 결과(state/run-log.md)에서 가져왔다.

## 1. Harness Overview

| 항목 | 내용 |
|---|---|
| 해결하려는 실패 | 회의록을 1page로 옮길 때 (1) 같은 입력이 매번 다른 형식·verdict로 나오고, (2) 미확인 수치·범위 밖 항목이 본문에 섞이며, (3) 외부 결정 대기 상태가 추적되지 않는 것 |
| 사용할 상황 | 회의록 한 건 → 1page 7칸 초안 변환. ask/build/review 분업과 결정론 guard가 필요한 작업 |
| 사용하지 않을 상황 | 문제 정의 자체(어떤 회의를 다룰지), 성공 기준의 목표/지표 최종 결정, 1page 최종 채택 — 사람 몫 |
| 최종 산출물 | L5·L6 review를 통과한 7칸 1page 초안 + leaf별 verdict/route/run-log/checkpoint |
| 최종 판단자 | 1page를 받는 의사결정자 (requirements-contract의 reader) |

## 2. Workflow Layer

런타임 어댑터와 무관하게 항상 같은 graph. (5-2 런타임 독립의 workflow layer)

```text
requirements_contract        [chapter-02 통합: reader/decision/success/constraints 잠금]
  -> spec_contract           [chapter-03 통합: 7칸 output contract + 고정·남김·질문]
  -> session_surface         [chapter-03 통합: SessionStart + 메타/도메인 스킬 + PostToolUse→precheck]
  -> structured_contract     [handoff schema + 1page contract SHA-256 잠금]
  -> state_store.load_or_init [run-log replay + 최신 checkpoint 복원]
  -> fill_handoff            [leaf + prior verdict + source -> brief 1장]
  -> dispatch_plan           [ask/build/review leaf role + evidence slice]
  -> invoke_runtime_adapter  [replay / codex / claude — adapter boundary]
  -> collect_handoff         [handoff 정규화, 비계약(대화) 상태 폐기]
  -> review_gate             [결정론 precheck: format · meeting.md:NN 줄 실재 · 60% 단정]
  -> review_parallel         [L5 인용 보존 ∥ L6 도메인/범위/FN]
  -> merge_verdict           [한쪽 pass 보존, 실패만 재작업, 이전 pass 퇴행 검사]
  -> route( exit / retry / ask-back(requirements_update) / blocked exit )
```

## 3. Runtime Adapter

workflow는 그대로 두고 이 표의 adapter만 런타임마다 갈아끼운다. 분기점은 어댑터 하나(Ouroboros `providers/factory.py` 구조).

| runtime | 실행 surface | adapter가 책임질 것 |
|---|---|---|
| Codex | `codex exec --json --output-last-message` (사실·범위 검사 L6), `.codex/agents/*.toml` | leaf 실행, codex CLI 비대화형 호출, stdout 진행로그와 최종 메시지 분리 |
| Claude | Claude Agent SDK `query()` (인용 검사 L5), `.claude/agents/*.md` | leaf 실행, 기존 Claude Code 인증 사용(raw API 키 불필요), 텍스트 블록 수집 |
| 기타(replay/CLI) | `source/handoffs/` 녹화 산출 재생 | API·CLI 없이 결정론 재현. 4장 실제 sub agent 산출을 contract 상태에 맞게 회수 |

- **workflow(런타임 무관, 유지)**: graph node 순서·route 전이·handoff 계약·결정론 guard.
- **adapter(런타임별, 교체)**: `invoke_runtime_adapter`의 leaf 실행, `review_parallel`의 L5/L6 런타임.

## 4. Demo Run

실행 명령 (canonical: run-1 결정론 local → resume는 실제 런타임 live):

```bash
# RUN 1 — fresh init, 성공 기준 metric 미확정 → review L6가 ask-back
python3 harness_v2_server.py run \
  --workspace . --source-workspace source --source inputs/meeting.md \
  --runtime codex --review-adapter local

# RESUME — inputs/answers.md(2장 채택)로 requirements_update → 실제 Claude Agent SDK ∥ codex exec 검수 → exit
python3 harness_v2_server.py resume --answers inputs/answers.md --review-adapter live
```

node별 input/output/verdict (state/run-log.md 실측):

| node | input | output | verdict/route |
|---|---|---|---|
| requirements_contract | contracts/requirements-contract.md | reader/decision/success/constraints 잠금 (sha=def2…) | pass (metric 미확정) |
| spec_contract | contracts/spec-contract.md | 7칸 output contract 잠금 (sha=a3d8…) | pass |
| session_surface | 3장 SessionStart·메타/도메인 스킬·PostToolUse | state/session-start-context.md 주입 | injected |
| structured_contract | requirements+spec sha | structured-contract.json 잠금 (sha=3124…) | pass |
| state_store.load_or_init | run-log.jsonl, checkpoints | replay 4 events / init | pass |
| fill_handoff | leaf + prior verdict + source | handoffs/_brief.md | pass |
| dispatch_plan | leaf graph | [L1-ask, L3L4-build] + evidence meeting.md:5,10,12,22,23 | pass |
| invoke_runtime_adapter | source/handoffs/, contract metric 상태 | handoff-L1-ask.md, handoff-L3L4-build.md | completed (precheck rc=0) |
| collect_handoff | build handoff | 필수 섹션 정규화, 비계약 상태 폐기 | pass |
| review_gate | build handoff, L1 handoff | review_gate.py format/line/60% + L1 leaf gate | pass |
| review_parallel (RUN 1, local) | build handoff(metric 미확정) | L5 인용=통과 ∥ L6 metric gate=재질문 | L5=통과, L6=재질문 |
| merge_verdict (RUN 1) | L5/L6 verdict | preserved=[L5], failed=[L6] | — |
| route (RUN 1) | merge | pending-external.md + checkpoint + resume_target=fill_handoff | **ask-back(requirements_update)** |
| requirements_update (RESUME) | inputs/answers.md(☑ 채택) | acceptance.metric 채움 + spec 성공 기준 갱신 + v2 re-freeze | pass |
| invoke_runtime_adapter (RESUME) | contract metric 확정 | answered build handoff | completed |
| review_parallel (RESUME, **live**) | build handoff + meeting.md | **Claude Agent SDK(L5 인용)=pass ∥ codex exec(L6 사실·범위)=pass** | L5=통과, L6=통과 |
| merge_verdict (RESUME) | L5/L6 | preserved=[L5,L6], failed=[] (regression 없음) | — |
| route (RESUME) | merge | handoffs/final-1page-draft.md | **exit** |

가이드 요구 검증 명령 (실제 통과):

```bash
$ python3 .codex/hooks/review_gate.py handoffs/handoff-L5-review.md --leaf L5   # route: pass (exit 0)
$ python3 .codex/hooks/review_gate.py handoffs/handoff-L6-review.md --leaf L6   # route: pass (exit 0)
```

## 5. Final Result

- 산출물: `handoffs/final-1page-draft.md` (7칸 + 보존 표현 3줄 큰따옴표 인용 + 확인 필요 항목 분리)
- 판단 근거: review_gate 결정론 통과 + L5 인용(Claude Agent SDK) 통과 + L6 사실·범위(codex exec) 통과
- 남은 불확실성 (확인 필요 항목): 최종 담당자·일정(월요일 정기 미팅), CS 문의 60% 수치 출처(추정·미확인), 환불 기준 명문화 가능 여부(운영팀 회신 대기), 시안·메일 작업 일정 최종 승인
- route: **ask-back → (requirements_update) → exit**
- 다음 행동: 위 4건의 외부 회신만 후속 추적. 1page 채택은 의사결정자가 닫는다.

## 6. Operating Notes

| 상황 | 해야 할 일 | 하지 말아야 할 일 |
|---|---|---|
| 새 입력 | inputs/meeting.md 교체 후 `run`. contract는 frozen이라 leaf가 임의로 바꾸지 못한다 | 회의록 밖 사실을 본문 결론으로 추가 |
| guard 실패 | review_gate precheck 위반 칸만 보정해 재제출(retry). 같은 사유 2회면 blocked exit | 전체 재작성, 의미 검수로 건너뛰기 |
| retry 반복 | retry_count_by_reason 누적. 2회면 last_good_checkpoint + pending 보존 | 무한 재작업 |
| ask-back (requirements/spec 갱신) | 2장 사각지대 두 질문을 pending-external.md로 남기고, 사람 채택(☑/☒)을 answers로 받아 requirements_update → v2 re-freeze → resume_target 복귀 | 미확정 metric을 추측으로 채우기 |
| 최종 판단 | exit는 7칸 충분 + guard 통과 + 불확실성 분리일 때만 | "더 좋아질 수 있음"을 retry 사유로 쓰기 |

## 7. Next Iteration

- capability가 부족해 adapter로 우회한 지점: leaf 실제 생성(ask/build)은 replay 어댑터로 4장 산출을 회수한다. 실제 신규 생성은 `--runtime claude/codex`에서 Task/codex subagent로 확장 가능.
- MCP로 코드화한 다음 node: `review_parallel` (review_server.py) — Claude Agent SDK ∥ codex exec 병렬 검수. 한쪽 실패 시 통과 보존을 코드로 강제.
- 사람에게 남겨야 하는 판단: 회의 대상 선택, 성공 기준의 목표/지표 결정, 1page 최종 채택, exit 선언.

---

## 부록 A. 2005-4-1 Ouroboros 원칙 → v2 구현 위치

| Ouroboros 원칙 | v2 구현 (코드/상태에서 보이는 곳) |
|---|---|
| `Seed` frozen, direction 실행 중 불변 | `contracts/*.md` + structured_contract SHA-256 잠금. leaf는 입력으로만 읽음. ask-back만 metric 1칸 갱신 후 v2 re-freeze |
| `EventStore` append-only + replay | `state/run-log.jsonl` append-only, `state_store.load_or_init`이 replay로 복원 |
| `CheckpointStore` 회전 + rewind 기록 | `state/checkpoints/*.json` (route.json → .1 → .2 회전), last_good.json, rewind_reason 필드 |
| `EvaluationPipeline` 3단 조기차단 | review_gate(결정론) 통과 후에만 review_parallel(의미). precheck 실패면 L5/L6로 안 넘김 |
| `RegressionDetector` 이전 통과 재실패 차단 | `merge_verdict`가 preserved_passes 저장, resume 후 이전 pass 깨지면 blocked exit |
| `ConvergenceCriteria` satisfice exit | route.exit은 7칸 충분 + guard 통과 + 불확실성 분리일 때만 |

## 부록 B. 결정론 재현 변형

live(LLM)는 비결정성이 있어, 같은 입력 → 같은 route 재현이 필요하면 `--review-adapter local`을 쓴다.
local 결정론 judge는 외부 호출 없이 실제 파일을 읽어 verdict를 낸다. 두 번 돌려 route·build handoff 해시가 동일함을 확인했다.

```bash
python3 harness_v2_server.py run    --review-adapter local   # → ask-back (재현 가능)
python3 harness_v2_server.py resume --answers inputs/answers.md --review-adapter local   # → exit (재현 가능)
```

live 병렬 검수만 따로 시연:

```bash
python3 harness_v2_server.py review --adapter live   # review_parallel node 단독: Claude Agent SDK ∥ codex exec
```
