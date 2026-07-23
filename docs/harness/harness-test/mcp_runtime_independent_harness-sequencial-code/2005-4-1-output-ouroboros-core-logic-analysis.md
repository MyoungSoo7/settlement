# Ouroboros 핵심 로직 분석

## 1. Observation Scope

- **분석 목적**: Ouroboros가 한 번의 막연한 요청을 닫힌 명세로 정제하고, 그 명세를 실행·평가·진화로 돌리는 동안 어떤 분업 경계·상태 채널·종료 판단을 코드로 강제하는지 관찰한다. 목적은 복제가 아니라, 본인 업무 하네스에 옮길 설계 원칙을 코드 근거에서 끌어오는 것이다.
- **비교할 내 업무 하네스**: 회의록 한 건(`inputs/meeting.md`)을 1page 7칸 초안(6칸 본문 + 확인 필요 1칸)으로 옮기는 분업형 하네스. 메인 루프는 fill_handoff → dispatch(sub agent) → review_gate → route 4단계, 상태판은 `state/run-log.md`, 리프는 L1 ask → L3+L4 build → L5 review → L6 review 직렬, 리프 1개가 sub agent 1명이고 입출력은 언제나 handoff 한 장이다.
- **관찰 범위**: README가 아니라 소스 트리 `src/ouroboros/*`의 실제 경로·심볼만 인용한다. 관찰 클론은 `/Users/jaegyu.lee/Project/ouroboros-gemini3`(origin=Q00/ouroboros, 브랜치 codex/capability-followup-review-nits), 모든 경로는 `src/ouroboros/` 기준 상대다.
- **실행 여부**: 설치·실행·성능 검증은 하지 않았다. 인용한 수치(0.2 / 0.3 / 0.95 / 2/3 / max_generations 30 등)는 소스에 리터럴로 적힌 기본값이며 런타임 측정값이 아니다. 코드 텍스트(시그니처·분기·정렬 키·docstring)에 적힌 의도만 근거로 삼는다.

## 2. 코드에서 찾을 네 가지

| 분석 축 | 출처 | Ouroboros에서 어디 (관찰 경로·심볼) | 본인 업무에 옮기면 (회의록→1page 하네스) |
|---|---|---|---|
| ① 4C 분업 경계 | 4장 | Contract = `core/seed.py::Seed`(frozen, direction 불변) + `core/seed_contract.py::SeedContract.from_seed` (불변 Seed를 실행 계약으로 해석하는 어댑터). <br />Context = `persistence/event_store.py::EventStore`(단일 events 테이블 append/replay) + `persistence/checkpoint.py::CheckpointStore`. <br />Control = `execution/double_diamond.py::DoubleDiamond.run_cycle_with_decomposition`(DISCOVER→DEFINE→atomicity 분기→DESIGN→DELIVER) + `orchestrator/runner.py::OrchestratorRunner.execute_seed`. <br />Confidence = `evaluation/pipeline.py::EvaluationPipeline`(Stage1 mechanical→Stage2 semantic→Stage3 consensus) | Contract = handoff 양식 + output contract (handoff 한 장이 Seed에 해당, 한 번 정한 양식은 잠근다). <br />Context = `state/run-log.md` 상태판. <br />Control = 메인 루프 dispatch·review_gate·route 전이. <br />Confidence = L5·L6 검수 verdict + 결정론 3검사(format · meeting.md:NN 줄 실재 · 금지값 60% 단정) |
| ② Capability 레이어 | 5-2 | Workflow(어디서나 같음) = `execution/double_diamond.py`의 단계 순서(`Phase` enum next_phase/order), `evaluation/pipeline.py`의 3단계 게이트, `evolution/loop.py`의 세대 순서. <br />Runtime(런타임마다 다름) = `providers/base.py::LLMAdapter`(Protocol, complete만), `providers/factory.py::create_llm_adapter`(claude_code↔codex 분기), `backends/capabilities.py::_CAPABILITIES`. <br />Integration = `orchestrator/capabilities.py`(MCP tool capability 그래프), `mcp/tools/*_handlers.py`, 레포 루트 `skills/`·`.claude-plugin/skills/` | Workflow = 메인 루프 4단계 · guard · route 전이표 · handoff 계약 (어떤 어댑터를 쓰든 같음). Runtime = dispatch 실행 어댑터(Claude · Codex · CLI), MCP transport, state_store 백엔드. Integration = SessionStart 주입 · PostToolUse hook · Task/슬래시 UI · skill 디렉터리 위치 |
| ③ SKILL.md 묶음 | 5-2 | `src/ouroboros/skills/`에는 SKILL.md 없음(`__init__.py`, `artifacts.py`만). 실제 SKILL.md는 레포 루트 `skills/<name>/SKILL.md` 20개 + `.claude-plugin/skills/<name>/SKILL.md` 20개 미러본으로 src/ 바깥에 위치. backend별 skill capability는 `backends/capabilities.py::SkillExecutionCapability` + `render_backend_skill_capability_guide`로 런타임 guidance 분리 | review-against-gate(L5+L6, 신규 1순위) / write-handoff(ask·build·review 3 agent 공용 양식) / using-task-harness(메타 루프, 이미 존재 → MCP 호출로 갱신). 묶음은 실행 코드(src/) 바깥, 런타임이 읽는 디렉터리에 둔다 |
| ④ Stop rule 멈춤 | 5-3 | exit/converge = `evolution/convergence.py::ConvergenceCriteria.evaluate`(유사도≥0.95 + eval/AC/regression/validation gate 모두 통과해야 CONVERGED). <br />consensus = `evaluation/consensus.py::ConsensusEvaluator`(2/3 다수결) + `evaluation/trigger.py::ConsensusTrigger`(uncertainty>0.3 / drift>0.3 시 Stage3 발동). <br />drift = `observability/drift.py::DRIFT_THRESHOLD`(0.3, is_acceptable). <br />regression = `evolution/regression.py::RegressionDetector`(이전 통과 AC가 최신 세대 실패 시 수렴 차단) | exit(satisfice — 7칸 충분 + guard 통과 + 불확실성 분리) / retry(입력·기준 충분한데 guard 위반, checkpoint→return node, 사유 한 줄만 보정) / ask-back(입력·목표 불명확, L1 ask 또는 사람) / blocked exit(같은 retry 사유 2회 또는 외부 회신 없음, last_good_checkpoint + pending 보존). "더 좋아질 수 있음"은 retry 사유 아님 |

## 3. 전체 연결 흐름

| 흐름 | 관찰 경로 | 역할 | 내 하네스에 주는 질문 |
|---|---|---|---|
| Interview | `bigbang/interview.py::InterviewEngine`(라운드별 Socratic 질문, `InterviewState`를 `~/.ouroboros/data/interview_{id}.json`에 영속) + `bigbang/ambiguity.py::AmbiguityScorer`(goal/constraint/success 명료도 채점) | 막연한 입력을 라운드 단위로 정제해 상태에 누적하고, `AMBIGUITY_THRESHOLD`(0.2) 이하로 내려와야 다음으로 넘어가게 막는다 | 회의록 입력이 모호할 때 바로 build로 넘기지 않고 L1 ask로 되돌리는 게이트가 내 하네스에 있는가? 모호도 임계 대신 "확인 필요 1칸"이 그 역할을 하는가? |
| Seed | `bigbang/seed_generator.py::SeedGenerator.generate`(모호도 게이트 통과 후에만 추출→`_build_seed`) → `core/seed.py::Seed`(goal·constraints·acceptance_criteria + ontology_schema·exit_conditions, frozen) | 게이트를 통과한 입력만 불변 명세로 조립한다. 한번 만든 direction은 잠겨서 실행 중에 변형되지 않는다 | handoff 양식과 output contract를 한번 정하면 리프가 실행 중에 바꾸지 못하게 잠그는가? Seed처럼 "본문에 들어갈 6칸 + 확인 필요 1칸"이 계약으로 고정되어 있는가? |
| Execute | `execution/double_diamond.py::DoubleDiamond.run_cycle_with_decomposition`(DISCOVER 발산→DEFINE 수렴→atomicity 분기→DESIGN→DELIVER), 비원자 AC는 `decompose_ac`로 2~5개 자식으로 쪼개 `_topological_sort_to_levels`로 레벨별 병렬·순차 | 한 AC를 정해진 단계 순서로 돌리고, 자식 실패가 부모로 전파되지 않게 격리(`validate_child_result`로 통합 전 구조 검증). 단 Seed→실행 결선은 이 디렉터리 밖(`OrchestratorRunner`) | 리프 직렬(ask→build→review)의 단계 순서가 코드로 강제되는가, 아니면 매번 즉흥인가? 한 리프 실패가 메인 루프 전체를 중단시키는가, 격리되어 route로 흘러가는가? |
| Evaluate | `evaluation/pipeline.py::EvaluationPipeline.evaluate`(Stage1 기계검사 → Stage2 의미평가 → Stage3 합의, 각 단계 실패 시 조기 종료) + `evaluation/checklist.py::build_run_feedback`(실패 AC를 reason과 함께 Run 재시도 입력으로 되돌림) | 비용 오름차순 3단계 게이트로 형식·의미·합의를 분리한다. 통과/거부는 이진(`final_approved`), 재시도는 실패 AC를 입력으로 되돌리는 형태 | 내 review_gate가 형식(format)·의미(인용↔주장 대조)·합의(L5+L6) 세 층으로 분리되어 있는가? 검수 실패가 막연한 "다시"가 아니라 "어느 AC가 왜 실패했는지" 한 줄로 build에 되돌아가는가? |
| Evolve | `evolution/loop.py::EvolutionaryLoop._run_loop`(세대 순서 wondering→reflecting→seeding→executing→evaluating) + `evolution/wonder.py::WonderEngine`(아직 모르는 것 생성) + `evolution/reflect.py::ReflectEngine`(다음 세대 Seed용 refined_goal 제안) | 평가 결과를 다음 세대 Seed로 되먹이는 자기참조 루프. Wonder의 should_continue=False는 질문이 0개일 때만 조기 종료, 질문이 남으면 게이트가 우선 | 1page 초안 한 건은 단발이라 세대 루프가 필요 없을 수 있다. 그래도 "검수 실패 → 사유 → 같은 자리 재시도"는 1세대짜리 evolve다. 몇 번까지 되먹일지(retry_count) 경계가 있는가? |

## 4. State and Rewind

| 행 | 관찰 경로 | 상태로 남기는 것 | 복귀·추적에 쓰는 법 |
|---|---|---|---|
| Event store | `persistence/event_store.py::EventStore`(append/append_batch/replay), `persistence/schema.py::events_table`(aggregate_type/aggregate_id/event_type/payload JSON 단일 테이블) | 모든 이벤트를 dot.notation.past_tense로 한 테이블에 append-only 누적. raw 구독 payload는 `sanitize_event_data_for_persistence`로 경계에서 정제 | `replay(aggregate_type, aggregate_id)`로 timestamp+id 순 재생해 상태 재구성. `get_events_after`는 rowid 커서로 증분 조회 → 내 `state/run-log.md`는 append-only로 쌓고 마지막 지점부터 다시 읽는 구조인가 |
| Checkpoints | `persistence/checkpoint.py::CheckpointStore.save`(파일 락 → `_rotate_checkpoints`로 current→.1→.2→.3 회전) + `CheckpointData.create`(SHA-256 해시) + `PeriodicCheckpointer`(기본 300초 간격) | phase 단위 상태 스냅샷을 `~/.ouroboros/data/checkpoints/` JSON으로, 무결성 해시 포함. event store와 분리된 두 번째 상태 채널 | `load`가 level 0→3 순으로 시도하며 무결성 실패 시 다음 단계로 롤백 → 내 checkpoint(input/spec/execution/review)도 손상·실패 시 직전 good 지점으로 되돌릴 수 있는가 |
| Rewind | `events/lineage.py::lineage_rewound`(from_generation/to_generation 기록 팩토리). 실제 적용은 영역 밖 `evolution/projector.py`·`evolution/loop.py::rewind_to` | 되돌림 자체를 이벤트로 남긴다(어디서 어디로 되감았는지). persistence는 기록·재생까지만, 해석·적용은 evolution 쪽에서 한다 | retry 시 checkpoint→return_node로 되돌릴 때 "왜 되돌렸는지(retry 사유)"를 run-log에 한 줄로 남기는가. blocked exit의 last_good_checkpoint가 이 rewind 기록과 같은 역할 |
| Failure history | `events/lineage.py::lineage_generation_failed`(generation·phase·error) + `lineage_generation_interrupted`(last_completed_phase·partial_state로 재개), `evolution/regression.py::RegressionDetector` | 실패와 중단을 상태로 남겨 재개·퇴행 감지에 쓴다. SIGINT 중단도 phase 단위로 어디까지 했는지 보존 | 같은 retry 사유가 2회 반복인지 판정하려면 실패 이력이 남아야 한다 → `retry_count_by_reason`을 run-log에 누적하는가. 이전에 통과한 칸이 재시도 후 깨졌는지(regression) 추적하는가 |

## 5. Evaluation Gate

| 검수 단계 | 확인하는 것 | 판단 결과 (종료/재시도/재질문) | 다음 행동 |
|---|---|---|---|
| 형식 검수 (Stage1 mechanical) | `evaluation/mechanical.py::MechanicalVerifier.verify` — lint/build/test/static/coverage를 무비용·AC-무관으로 실행, `CheckType` enum 순서 고정 | 실패면 즉시 조기 종료(다음 단계 진입 차단). 통과/거부 이진 | 내 하네스의 결정론 3검사 중 format 검사 자리. 형식 깨지면 의미 검수로 넘기지 않고 build로 되돌린다 |
| 의미 검수 (Stage2 semantic) | `evaluation/semantic.py::SemanticEvaluator` — AC 준수·목표정렬·drift·uncertainty·reward_hacking_risk를 LLM JSON으로. `build_evaluation_prompt`는 questions_used·evidence를 강제(비면 검증 실패 취급) | ac_compliance 실패면 멈추되, `ConsensusTrigger` 충족(uncertainty>0.3 등) 시 Stage3로 끌어올림. 실패 AC는 `build_run_feedback`로 재시도 입력 | 내 하네스의 "인용↔주장 의미 대조"와 meeting.md:NN 줄 실재 검사 자리. 불확실하면 거부가 아니라 합의로 한 번 더 본다 |
| 합의 검토 (Stage3 consensus) | `evaluation/consensus.py::ConsensusEvaluator`(다모델 독립 투표 2/3, 단일모델이면 advocate/devil/judge 다관점 폴백) + `DeliberativeConsensus`(2라운드 심의, Judge가 `FinalVerdict` APPROVED/REJECTED/CONDITIONAL) | majority_ratio≥0.66이면 approved. 최종 verdict가 멈춤 판정에 기여 | 여기서 L5·L6 두 verdict를 모은다. 한 검수만으로 애매하면 두 번째 관점(L6)으로 교차 확인 후 route 결정. **verification/ SpecVerifier**는 agent가 PASS라 거짓 주장한 경우 소스 대조로 `override_approval=False`로 뒤집는 정직성 검수 — 금지값 60% 단정 검사의 모델 |

## 6. MCP or Orchestrator Boundary

척추: "툴이 LLM을 쓴다" — MCP gate 도구가 gate이고, 그 도구가 의미 슬라이스를 위해 review sub agent(LLM)를 안에서 호출한다.

| 안쪽에 둘 것 | 바깥에 남길 것 | 이유 |
|---|---|---|
| 순서 강제: `evaluation/pipeline.py`의 Stage1→2→3 고정, `execution/double_diamond.py`의 Phase 순서, route 전이표·카운터 | 문제 정의: 무엇을 1page로 옮길지, 어떤 회의가 대상인지 | 순서·전이는 결정론으로 잠가야 매번 같이 적용된다. 문제 정의는 의미 판단이라 사람·바깥 몫이다 |
| 상태 저장: `persistence/event_store.py`의 append-only 이벤트 + run-log 스키마 | 원본 입력 제공: `inputs/meeting.md` 원문, 회의 맥락 | 상태 기록은 기계가 결정적으로 한다. 원본 입력은 바깥에서 들어오는 것이지 도구가 만들지 않는다 |
| 재시도·체크포인트: `_execute_phase_with_retry` 백오프, `CheckpointStore` 회전, retry_count_by_reason | 최종 수용 판단: exit 선언("여기서 멈추고 보고한다"), 의사결정자 확정 | 재시도 횟수·롤백은 규칙으로 돌릴 수 있다. "이 1page를 채택한다"는 책임 판단이라 사람이 닫는다 |
| 평가 게이트: review gate 결정론 슬라이스의 MCP 도구화(format·줄 실재·금지값 검사) | 결과 공유·의미 슬라이스: 인용↔주장 최종 의미 대조, 보고 대상에게 전달 | 결정론 슬라이스는 도구가 gate로 직접 판정. 그 안에서 의미가 필요한 슬라이스만 review sub agent(LLM)를 호출한다 — 툴이 LLM을 쓴다 |

## 7. Transfer to My Harness

| Ouroboros 관찰값 | 내 하네스에 적용할 원칙 | 그대로 복사하지 않을 것 |
|---|---|---|
| `AmbiguityScorer` + `AMBIGUITY_THRESHOLD`(0.2)가 모호하면 Seed 생성을 막음 | 입력이 모호하면 build로 못 넘어가게 한 칸 게이트를 둔다. 임계는 점수 대신 "확인 필요 1칸이 비었는가"로 단순화 | LLM 채점기 자체. 회의록 1건에 0~1 모호도 스코어링은 과하다 |
| `Seed`가 frozen이고 direction이 실행 중 불변 | handoff 양식·output contract를 한번 정하면 잠그고, 리프가 실행 중 바꾸지 못하게 한다 | ontology_schema·evaluation_principles까지 갖춘 Seed 전체 구조. 내겐 handoff 한 장으로 충분 |
| 평가가 비용 오름차순 3단계 게이트(mechanical→semantic→consensus)로 분리 | review_gate를 format(무비용) → 의미 대조 → L5+L6 합의 순으로 분리하고, 앞 단계 실패 시 뒤로 안 넘긴다 | Stage별 LLM 다모델 합의 인프라. L5+L6 두 sub agent 교차로 충분 |
| `EventStore` 단일 events 테이블 append-only + `CheckpointStore` 두 번째 채널 | `state/run-log.md`를 append-only 상태판으로, checkpoint(input/spec/execution/review)를 별도로 남긴다 | SQLite·SHA-256 무결성·회전(.1/.2/.3) 인프라. md 한 장 + checkpoint_id로 충분 |
| `ConvergenceCriteria`가 "유사하되 실패·퇴행·미진화 없을 때만" 종료 | exit은 "7칸 충분 + guard 통과 + 불확실성 분리"일 때만. "더 좋아질 수 있음"은 retry 사유 아님 | 유사도·진동·반복 wonder 다중 게이트. 1page 단발에는 satisfice 한 줄 기준이면 된다 |
| `RegressionDetector`가 이전 통과 AC의 재실패를 수렴 차단 | retry 후 이전에 통과한 칸이 깨졌는지 본다. 같은 사유 2회면 blocked exit | 세대 간 lineage 비교 자료구조. retry_count_by_reason 카운터로 충분 |

## 8. Final Summary

- **순서 강제**: Ouroboros는 단계 순서를 즉흥에 맡기지 않는다. `Phase` enum(next_phase/order) · `EvaluationPipeline`의 Stage1→2→3 · `EvolutionaryLoop`의 세대 순서가 모두 코드로 적혀 있고, 실패 시 다음 단계 진입을 조기 차단한다.
- **상태 저장**: 진실원천은 `EventStore`의 단일 events 테이블 하나(append-only, replay로 재구성)이고, phase 복구용 JSON 체크포인트가 두 번째 채널로 분리되어 `UnitOfWork`가 둘을 묶는다.
- **재시도·rewind**: 단계 단위 백오프(`_execute_phase_with_retry`)와 체크포인트 회전·롤백(`CheckpointStore` level 0→3), 되돌림을 이벤트로 남기는 `lineage_rewound`까지 — 재시도와 되감기가 모두 상태로 기록된다.
- **종료 판단**: `ConvergenceCriteria`는 "유사하면 멈춘다"가 아니라 "유사하되 eval·AC·regression·미진화 게이트를 모두 통과할 때만 멈춘다". 시간 기반 강제 종료는 `GenerationProgressWatchdog`가 따로 맡는다.
- **내 하네스 반영 최소 원칙(1~2개)**: (1) review_gate를 format(무비용 결정론) → 의미 대조 → L5+L6 합의 순으로 분리하고, 앞 단계 실패면 뒤로 넘기지 않는다. (2) exit는 satisfice 게이트("7칸 충분 + guard 통과 + 불확실성 분리")로만 닫고, 같은 retry 사유 2회면 blocked exit로 멈춰 last_good_checkpoint와 pending을 보존한다 — 추정으로 채우지 않는다.

## 9. 빌드 재료 (→ 2005-4-3)

| 어댑터 | 경로 (실제) | 공통 인터페이스 (complete) | 4-3에서 쓸 곳 |
|---|---|---|---|
| Claude 어댑터 | `providers/claude_code_adapter.py::ClaudeCodeAdapter`(class line 95, complete line 301), Claude Agent SDK 런타임 | `async def complete(self, messages: list[Message], config: CompletionConfig) -> Result[CompletionResponse, ProviderError]` (`providers/base.py::LLMAdapter` Protocol, line 128-146) | 여기서 인용 출처를 검사한다 |
| Codex 어댑터 | `providers/codex_cli_adapter.py::CodexCliLLMAdapter`(class line 89, complete line 1169), `codex exec` CLI 런타임 | 위와 동일 시그니처. 두 어댑터 모두 `providers/base.py`의 Message/MessageRole/CompletionConfig/CompletionResponse 공유 | 사실·범위 검사 |

분기점은 `providers/factory.py::create_llm_adapter` 하나 — `backend=='claude_code'`면 ClaudeCodeAdapter, `=='codex'`면 CodexCliLLMAdapter로 반환 타입(LLMAdapter)만 같게 두고 런타임만 가른다. 두 어댑터가 같은 `complete()` 인터페이스에 런타임만 다르다는 점이 곧 review_parallel(인용 검사 ∥ 사실·범위 검사) 병렬 검수의 빌드 재료다.

**확인 필요**: 소스 트리(`src/ouroboros/`)에 `review_parallel`이라는 코드 심볼은 grep 0건으로 존재하지 않는다. 위 "review_parallel 재료" 연결은 실습(2005-4-3) 측 개념 명칭이며, 두 어댑터가 같은 인터페이스를 공유한다는 코드 사실까지만 관찰로 확정된다.
