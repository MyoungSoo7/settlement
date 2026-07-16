# 2005-4-1. [Case Study] Ouroboros: 끝없는 루프를 통제하는 프레임워크

## Source Mapping

- Curriculum: `curriculum.md` row `2005-4-1` in `5-4. 최종 프로젝트 및 케이스 스터디` defines the clip as `[Case Study] Ouroboros: 끝없는 루프를 통제하는 프레임워크`, with key message `순서, 상태, 재시도를 코드로 강제해 폭주를 막는 구현 사례` and required output `Ouroboros 핵심 로직 분석`.
- Interview: `interview.md` provides the conceptual boundary for this worksheet: Use the interview framing that Ouroboros is a source of design principles: ordered execution, evaluation, evolution, event history, and rewind should be adapted to the learner workflow.
- Ouroboros grounding: `interview.md` frames Ouroboros around Socratic Interview, Seed, AC Tree, deterministic execution, Ralph-style evaluation, evolution, event store, checkpoints, and rewind. This worksheet asks learners to observe those concepts as structure and then translate them into their own workflow, not to clone the project.
- Reference repo: Ouroboros is approved by `decks/approved-source-checklist.md` for chapter 05 through `lecture-decks.seed.yaml` reference repo `https://github.com/Q00/ouroboros`; distinguish interview framing from repo-path observation and do not claim install or runtime behavior.
- Guardrail: follow `decks/approved-source-checklist.md`; do not add new external examples, unsupported statistics, prices, discounts, volatile marketing claims, or unverifiable repo behavior.

## Practice Task and Prompt

- Task: create `Ouroboros 핵심 로직 분석` for clip `2005-4-1` so the learner can apply the curriculum message `순서, 상태, 재시도를 코드로 강제해 폭주를 막는 구현 사례` to their own work.
- Prompt to paste into AI:

```text
Fast Campus 하네스 엔지니어링 실습 2005-4-1을 도와줘.

Curriculum mapping:
- Module: 5-4. 최종 프로젝트 및 케이스 스터디
- Clip: [Case Study] Ouroboros: 끝없는 루프를 통제하는 프레임워크
- Key message: 순서, 상태, 재시도를 코드로 강제해 폭주를 막는 구현 사례
- Required output: Ouroboros 핵심 로직 분석

Interview mapping:
- Use the interview framing that Ouroboros is a source of design principles: ordered execution, evaluation, evolution, event history, and rewind should be adapted to the learner workflow.
- Use Ouroboros as reference-grounded support for observation: Socratic Interview -> Seed -> AC Tree -> execute/evaluate/evolve -> event store/checkpoints/rewind. Separate interview framing from repo path observation, and do not claim install, runtime behavior, benchmarks, or repo behavior beyond visible paths.

Task:
내 입력을 바탕으로 `Ouroboros 핵심 로직 분석` 초안을 작성해줘. 아래 실습자료의 작성 템플릿 구조를 따르고, 모르는 정보는 추측하지 말고 "다시 물을 질문" 또는 "확인 필요"로 남겨.

Boundaries:
- curriculum.md와 interview.md의 범위를 벗어난 외부 사례를 추가하지 않는다.
- 검증되지 않은 수치, 가격, 할인, 마케팅성 주장을 넣지 않는다.
- 최종 책임 판단이 필요한 항목은 사람이 결정할 수 있게 분리한다.

My input:
[여기에 내 업무 상황, 원문, 제약, 기존 산출물을 붙인다]
```

## 목적

Ouroboros를 MCP형 하네스의 레퍼런스 사례로 읽고, 인터뷰, Seed, 실행, 평가, 진화 흐름에서 순서, 상태, 재시도, 종료 판단이 어떻게 드러나는지 분석한다. 이 자료의 목표는 Ouroboros를 복제하는 것이 아니라, 4장 OMX case study와 분업형 하네스 v1에서 남겨 둔 상태 저장, checkpoint, 복귀 지점의 빈칸을 내 업무용 하네스에 맞게 채우는 관찰 기준을 얻는 것이다.

## Exercise Objectives

- Ouroboros를 MCP형 하네스의 Chapter 05 reference case로 읽고, curriculum row `2005-4-1`의 핵심 메시지인 순서, 상태, 재시도 강제 구조를 표시한다.
- Socratic Interview, Seed, AC Tree, execute, evaluate, evolve, event store, checkpoints, rewind 흐름을 입력 정제, 계약, 검수 기준, 실행, 평가, 반복, 기록, 복귀 지점의 관찰 언어로 정리한다.
- 4장의 `node / edge / guard / route`가 5장에서 `Seed / AC Tree / event store / rewind`로 어떻게 확장되는지 표시한다.
- 승인된 repo 경로 관찰과 `interview.md`의 철학적 설명을 구분해 기록한다.
- Ouroboros 관찰값을 내 업무 하네스의 질문, 명세, 실행, 검수, 운영 기록 원칙으로 옮긴다.
- 설치, 실행 성공, 성능, 사용량, 스타 수, 시장성처럼 승인되지 않은 주장 없이 구조 관찰만으로 분석을 완성한다.

## Ouroboros Reference Grounding

이 실습은 Ouroboros 자체가 사례이므로, 참조 근거를 명확히 나누어 사용한다. `curriculum.md`는 case-study 배치와 산출물을 정하고, `interview.md`는 설계 철학을 제공하며, 승인된 repo 경로는 구조 관찰 범위만 제공한다.

| 근거 유형 | 사용할 수 있는 내용 | 분석에 쓰는 방식 |
|---|---|---|
| Curriculum row `2005-4-1` | 끝없는 루프를 통제하는 프레임워크, 순서/상태/재시도 강제, `Ouroboros 핵심 로직 분석` 산출물 | 실습 목적과 완료 기준을 고정한다. |
| `interview.md` Ouroboros framing | Socratic Interview, Seed, AC Tree, Ralph loop, deterministic execution, event store, rewind, 도메인별 하네스 사고방식 | 구조를 해석하는 개념 렌즈로 사용한다. |
| Approved repo mapping | `README.md`, `src/ouroboros/bigbang/`, `core/`, `execution/`, `orchestrator/`, `evaluation/`, `evolution/`, `persistence/`, `events/`, `providers/` | 설치나 실행 없이 파일/디렉터리 경계를 관찰한다. |
| Source-fidelity guardrail | repo 행동, 성능, 채택 규모, 스타 수, 가격, 마케팅성 정보는 근거로 쓰지 않음 | 확인되지 않은 주장을 분석에서 제외한다. |

Source boundary: Ouroboros는 Chapter 05의 승인된 reference case다. 이 자료는 관찰 가능한 구조와 인터뷰 기반 설계 언어만 사용하며, 프로젝트의 런타임 동작이나 생산성 효과를 보장하지 않는다.

## 권장 시간

35분

## 준비물

- 2005-1-3 MCP Candidate Flow
- 2005-2-3 Runtime-independent Harness Graph
- 2005-3-3 Loop Protocol
- 4장에서 만든 분업형 하네스 v1 (4C 네 칸 정리본)
- 4-4 OMX case study에서 남긴 state/checkpoint/rewind 질문
- 강의 deck의 Ouroboros 관찰 경로 메모 (4C × Ouroboros 구조도)
- 금지사항: 설치나 실행 검증 없이 런타임 성능, 사용량, 스타 수, 시장성 같은 주장을 추가하지 않기

## 입력

아래 입력을 먼저 채운다.

| 항목 | 내용 |
|---|---|
| 관찰 목적 | 순서, 상태, 재시도, 종료 판단 구조를 찾는다 |
| 비교할 내 업무 하네스 |  |
| 참고할 산출물 | MCP Candidate Flow / Runtime-independent Harness Graph / Loop Protocol |
| 4장에서 남긴 운영 빈칸 | state / checkpoint / rewind 질문 |
| 관찰 범위 | README, `src/ouroboros/bigbang/`, `core/`, `execution/`, `orchestrator/`, `evaluation/`, `evolution/`, `persistence/`, `events/`, `providers/` |
| 실행 여부 | 설치 또는 실행 검증 없음. 구조 관찰만 수행 |
| 최종 산출물 | Ouroboros 핵심 로직 분석 |

## 단계별 진행

1. README를 기준으로 interview, seed, execute, evaluate, evolve 흐름을 순서대로 적는다.
2. `src/ouroboros/bigbang/`에서 Socratic Interview(`interview.py`)와 Seed 생성(`seed_generator.py`)이 입력 정제·명세화에 해당하는지 표시한다. AC Tree 자료구조는 bigbang이 아니라 `core/ac_tree.py`(ACTree·ACNode·ACStatus)에 있고, bigbang Seed의 acceptance_criteria는 평면 tuple이다. 둘이 어디서 갈라지는지 함께 적는다.
3. 실행 진입점을 둘로 나눠 본다. `src/ouroboros/execution/double_diamond.py`(class DoubleDiamond)가 발산·수렴 단계 엔진이고, Seed를 실행으로 잇는 진입점은 `src/ouroboros/orchestrator/runner.py`의 `OrchestratorRunner.execute_seed`다.
4. `src/ouroboros/evaluation/`에서 검수 단계별 평가가 종료, 재시도, 재질문 판단을 어떻게 나누는지 적는다.
5. `src/ouroboros/evolution/`에서 Wonder / Reflect cycle과 convergence detection이 반복을 어떻게 통제하는지 적는다.
6. `src/ouroboros/persistence/`에서 event store(`event_store.py` EventStore)와 checkpoint(`checkpoint.py` CheckpointStore)가 상태를 어떻게 남기는지 적는다. rewind는 persistence가 아니라 `src/ouroboros/events/lineage.py`의 `lineage_rewound`(기록)와 `evolution/projector.py`·`evolution/loop.py`의 `rewind_to`(적용)로 갈라져 있으니, 기록과 적용을 나눠 표시한다.
7. 관찰한 경로마다 순서 강제, 상태 저장, 재시도, 종료 판단 중 어떤 역할을 하는지 표시한다.
8. Ouroboros의 구조를 내 업무용 하네스에 그대로 복사하지 않고, 참고할 설계 원칙만 따로 적는다.
9. 최종 분석에 전체 연결 파이프라인, 상태/복귀 지점, 검수 기준, MCP/orchestrator 경계 네 축이 모두 들어갔는지 확인한다.
10. 코드에서 찾을 네 가지를 §2 표에 채운다 — ① 4C 분업 경계(Contract·Context·Control·Confidence)가 코드 어디에 있는지 ② Capability 레이어(workflow / runtime / integration)가 어떻게 나뉘는지 ③ SKILL.md 묶음이 어디에 있는지 ④ 멈춤 로직(exit·consensus·drift·regression)이 어느 네 곳인지. 각 항목이 어느 경로를 가리키는지, 본인 업무에 옮기면 어떤 칸이 채워지는지 한 줄씩 적는다.
11. 마지막으로 `src/ouroboros/providers/`를 연다. `base.py`의 `LLMAdapter`가 `complete(messages, config)` 하나로 통일돼 있고, `claude_code_adapter.py`·`codex_cli_adapter.py`가 그 구현이다. "같은 인터페이스, 런타임만 다름"을 확인하고, 이 Claude·Codex 어댑터 한 쌍이 2005-4-3에서 만들 `review_parallel`(인용 ∥ 사실 병렬 검수)의 재료임을 §9에 메모한다.

## 작성 템플릿

````markdown
# Ouroboros 핵심 로직 분석

## 1. Observation Scope
| 항목 | 내용 |
|---|---|
| 분석 목적 | 순서, 상태, 재시도, 종료 판단 구조 관찰 |
| 비교할 내 업무 하네스 | [내 업무 하네스 이름] |
| 관찰 범위 | README, src/ouroboros/* 주요 경로 |
| 실행 여부 | 설치 또는 실행 검증 없음 |

## 2. 코드에서 찾을 네 가지 (NEW)
| 분석 축 | 출처 | Ouroboros에서 어디 | 본인 업무에 옮기면 |
|---|---|---|---|
| ① 4C 분업 경계 | 4장 | Contract(Seed) · Context(event store) · Control(execute·evaluate) · Confidence(consensus) | 분업형 하네스 v1의 네 칸 |
| ② Capability 레이어 | 5-2 | Workflow(core/) · Runtime(providers/ 6 native + α) · Integration(mcp·cli·tui) | 본인 업무 3 레이어 |
| ③ SKILL.md 묶음 | 5-2 | 레포 루트 `skills/<name>/SKILL.md`(20개) + `.claude-plugin/skills/` 미러. `src/ouroboros/skills/`엔 SKILL.md가 없다(실행 코드만) | 본인 SKILL.md 묶음 후보 |
| ④ Stop rule 멈춤 | 5-3 | exit · consensus · observability/drift · evolution/regression | 본인 exit·retry·ask-back loop |

## 3. 전체 연결 흐름
| 흐름 | 관찰 경로 | 역할 | 내 하네스에 주는 질문 |
|---|---|---|---|
| Interview | README, src/ouroboros/bigbang/ | [요구사항을 묻고 고정] | [내 하네스는 무엇을 먼저 물어야 하는가?] |
| Seed | src/ouroboros/bigbang/seed_generator.py, core/seed.py | [실행 계약으로 변환] | [어떤 내용을 바뀌지 않는 계약으로 둘 것인가?] |
| Execute | src/ouroboros/execution/, orchestrator/runner.py | [계약에 따라 실행] | [실행 순서는 어디서 강제할 것인가?] |
| Evaluate | src/ouroboros/evaluation/ | [결과 검수] | [pass/retry/ask-back route은 무엇인가?] |
| Evolve | src/ouroboros/evolution/ | [개선 루프] | [반복을 언제 멈출 것인가?] |

## 4. State and Rewind
| 관찰 항목 | 관찰 경로 | 상태로 남기는 것 | 복귀 또는 추적에 쓰는 법 |
|---|---|---|---|
| Event store | src/ouroboros/persistence/ | [실행 이벤트] | [나중에 흐름을 재구성] |
| Checkpoints | src/ouroboros/persistence/ | [중간 산출물/판정] | [되돌아갈 기준점] |
| Rewind | events/lineage.py(기록) · evolution/projector.py·loop.py(적용) | [복귀 대상 세대] | [실패 전 상태로 되돌린다] |
| Failure history | events/lineage.py · evolution/regression.py | [실패 원인/판정] | [같은 실패 반복 방지] |

## 5. Evaluation Gate
| 평가 게이트 | 확인하는 것 | 판단 결과 | 다음 행동 |
|---|---|---|---|
| 형식 검수 | [형식/빌드/필수 항목] | 종료 / 재시도 / 재질문 | [통과 또는 수정 지시] |
| 의미 검수 | [목표와 의미 충족] | 종료 / 재시도 / 재질문 | [요구사항 재확인] |
| 합의 검토 | [판단이 애매한 부분] | 종료 / 재시도 / 재질문 | [추가 검토 또는 멈춤] |

## 6. MCP or Orchestrator Boundary
| 안쪽에 둘 것 | 바깥에 남길 것 | 이유 |
|---|---|---|
| 순서 강제 | 문제 정의 | [사람이 목적을 정하고 시스템이 순서를 지킴] |
| 상태 저장 | 원본 입력 제공 | [실행 기록은 남기되 입력 판단은 사람이 제공] |
| 재시도와 체크포인트 | 최종 수용 판단 | [반복은 자동화하되 수용은 사람이 결정] |
| 평가 게이트 | 결과 공유 | [검수는 강제하고 공유 맥락은 팀이 정함] |

## 7. Transfer to My Harness
| Ouroboros 관찰값 | 내 하네스에 적용할 원칙 | 그대로 복사하지 않을 것 |
|---|---|---|
| [관찰한 구조] | [내 업무에 맞춘 최소 원칙] | [업무에 맞지 않는 구현 세부] |

## 8. Final Summary
- 순서 강제 관찰: [어떤 순서가 흐름을 지키는가]
- 상태 저장 관찰: [무엇을 기록해야 이어갈 수 있는가]
- 재시도 또는 rewind 관찰: [언제 반복/복귀해야 하는가]
- 종료 판단 관찰: [언제 멈춰야 하는가]
- 내 하네스에 반영할 최소 원칙: [바로 적용할 1-2개 원칙]

## 9. 빌드 재료 (→ 2005-4-3)
| 어댑터 | 경로 | 공통 인터페이스 | 4-3에서 쓸 곳 |
|---|---|---|---|
| Claude | src/ouroboros/providers/claude_code_adapter.py | complete(messages, config) | [인용 출처 검사] |
| Codex | src/ouroboros/providers/codex_cli_adapter.py | 〃 (base.py의 LLMAdapter) | [사실·범위 검사] |
- 두 어댑터는 같은 complete() 인터페이스를 쓰고 런타임만 다르다 (5-2 런타임 독립). 이 한 쌍이 4-3 review_parallel 병렬 검수의 재료다.
````

## 예시

````markdown
# Ouroboros 핵심 로직 분석

## 1. Observation Scope
| 항목 | 내용 |
|---|---|
| 분석 목적 | 순서, 상태, 재시도, 종료 판단 구조 관찰 |
| 비교할 내 업무 하네스 | 고객 문의 답변 초안 검수 하네스 |
| 관찰 범위 | README, src/ouroboros/* 주요 경로 |
| 실행 여부 | 설치 또는 실행 검증 없음 |

## 2. 코드에서 찾을 네 가지
| 분석 축 | Ouroboros에서 어디 | 내 하네스에 옮기면 |
|---|---|---|
| ① 4C 분업 경계 | Contract(Seed)·Context(event store)·Control(execute·evaluate)·Confidence(consensus) | 답변 초안 작성·검수의 네 칸 |
| ② Capability 레이어 | core/ · providers/ · mcp·cli·tui | 답변 양식(고정)·LLM 런타임(교체)·채널 연동 |
| ③ SKILL.md 묶음 | skills/*/SKILL.md · commands/ | 문의 분류·답변 초안·톤 검수 스킬 |
| ④ Stop rule 멈춤 | exit·consensus·observability/drift·evolution/regression | 검수 통과·재작성·사람 에스컬레이션 |

## 3. 전체 연결 흐름
| 흐름 | 관찰 경로 | 역할 | 내 하네스에 주는 질문 |
|---|---|---|---|
| Interview | README, src/ouroboros/bigbang/ | 사람의 암묵지를 질문으로 끌어내는 입력 정제 단계 | 내 업무는 어떤 질문을 먼저 고정해야 하나? |
| Seed | README, src/ouroboros/bigbang/ | 실행 가능한 명세와 AC 단위로 정리하는 단계 | 내 명세는 검수 가능한 단위로 쪼개졌나? |
| Execute | src/ouroboros/execution/, orchestrator/runner.py | Seed를 실행 흐름으로 넘기는 단계 | 실행이 명세 없이 시작되지 않게 막았나? |
| Evaluate | src/ouroboros/evaluation/ | 결과를 바로 완료하지 않고 평가 게이트에 통과시키는 단계 | 검수 실패와 입력 부족을 구분하나? |
| Evolve | src/ouroboros/evolution/ | 반복을 다음 세대 입력으로 연결하고 수렴 여부를 본다 | 같은 실패를 반복하지 않게 기록하나? |

## 4. State and Rewind
| 관찰 항목 | 관찰 경로 | 상태로 남기는 것 | 복귀 또는 추적에 쓰는 법 |
|---|---|---|---|
| Event store | src/ouroboros/persistence/ | 세대별 결정과 실행 결과 | 왜 현재 결과가 나왔는지 추적한다 |
| Checkpoints | src/ouroboros/persistence/ | 돌아갈 수 있는 중간 지점 | 실패 시 처음부터 반복하지 않는다 |
| Rewind | events/lineage.py · evolution/projector.py | 이전 세대로 되돌릴 기준 | 잘못된 방향의 반복을 끊는다 |
| Failure history | events/lineage.py · evolution/regression.py | 평가 실패 사유 | 다음 재시도 조건으로 사용한다 |

## 5. Evaluation Gate
| 평가 게이트 | 확인하는 것 | 판단 결과 | 다음 행동 |
|---|---|---|---|
| 형식 검수 | 형식, 빌드, 테스트처럼 기계적으로 확인할 수 있는 조건 | 재시도 | 실패 사유를 기록하고 실행 또는 수정 단계로 복귀 |
| 의미 검수 | 산출물이 목표와 AC에 맞는지 | 종료 또는 재시도 | 충분하면 종료, 부족하면 실패 사유를 반영 |
| 합의 검토 | 불확실성이 큰 판단을 여러 관점으로 확인 | 재질문 또는 보류 | 기준이 비어 있으면 사람 판단으로 넘김 |

## 6. MCP or Orchestrator Boundary
| 안쪽에 둘 것 | 바깥에 남길 것 | 이유 |
|---|---|---|
| 순서 강제 | 문제 정의 | 실행 순서는 반복 가능해야 하지만 문제 선택은 사람이 한다 |
| 상태 저장 | 원본 입력 제공 | 결정과 실패 이력은 남기되 원본 맥락은 사람이 제공한다 |
| 재시도와 체크포인트 | 최종 수용 판단 | 실패 복귀는 구조화하고 최종 책임은 사람이 가진다 |
| 평가 게이트 | 결과 공유 | 검수는 안쪽에서 돕고 공유 방식은 업무 맥락에 맞춘다 |

## 7. Transfer to My Harness
| Ouroboros 관찰값 | 내 하네스에 적용할 원칙 | 그대로 복사하지 않을 것 |
|---|---|---|
| Interview -> Seed -> Execute -> Evaluate -> Evolve 흐름 | 문의 답변도 입력 정리 -> 명세 -> 실행 -> 검수 -> 종료로 고정한다 | Ouroboros의 전체 구조나 이름을 그대로 쓰지 않는다 |
| event store와 checkpoints | 검수 실패 사유와 복귀 지점을 기록한다 | 모든 상태를 코드 저장소 수준으로 과하게 만들지 않는다 |
| 평가 게이트 | 완료 전 검수 단계를 반드시 통과시킨다 | 사람 판단이 필요한 일을 자동 완료 처리하지 않는다 |

## 8. Final Summary
- 순서 강제 관찰: README와 주요 경로가 interview, seed, execute, evaluate, evolve 흐름으로 이어진다.
- 상태 저장 관찰: persistence 경로가 event store, checkpoints, rewind 관찰 지점이다.
- 재시도 또는 rewind 관찰: 평가 실패 후 복귀 지점을 남기고 반복을 통제한다.
- 종료 판단 관찰: 검수 기준이 완료, 재시도, 재질문을 나누는 역할을 한다.
- 내 하네스에 반영할 최소 원칙: 명세 없는 실행을 막고, 검수 실패 사유와 복귀 지점을 기록한다.

## 9. 빌드 재료 (→ 2005-4-3)
| 어댑터 | 경로 | 공통 인터페이스 | 4-3에서 쓸 곳 |
|---|---|---|---|
| Claude | src/ouroboros/providers/claude_code_adapter.py | complete(messages, config) | 인용 출처 검사 |
| Codex | src/ouroboros/providers/codex_cli_adapter.py | 〃 (base.py의 LLMAdapter) | 사실·범위 검사 |
- 두 어댑터는 같은 complete() 인터페이스, 런타임만 다르다. 이 한 쌍을 4-3 review_parallel(인용 ∥ 사실 병렬)로 그대로 가져간다.
````

## 완료 기준

- Ouroboros 핵심 로직 분석에 관찰 범위와 실행 여부가 적혀 있다.
- 전체 연결 파이프라인에는 Interview, Seed, Execute, Evaluate, Evolve가 모두 포함되어 있다.
- 상태/복귀 지점 섹션에는 event store, checkpoints, rewind 중 필요한 관찰 항목이 포함되어 있다.
- 검수 기준 섹션에는 검수 단계별 평가가 종료, 재시도, 재질문 판단과 연결되어 있다.
- MCP/orchestrator 경계에는 안쪽에 둘 것과 바깥에 남길 것이 분리되어 있다.
- 최종 요약에는 순서 강제, 상태 저장, 재시도 또는 rewind, 종료 판단 관찰이 모두 포함되어 있다.
- Ouroboros를 복제 대상으로 쓰지 않고 내 업무용 하네스의 설계 원칙으로만 전환했다.
- `providers/`의 Claude·Codex 어댑터 한 쌍과 공통 `complete()`를 관찰해 2005-4-3 빌드 재료로 §9에 메모했다.

## 제출/검토 체크리스트

- [ ] 산출물 이름은 Ouroboros 핵심 로직 분석이다.
- [ ] 분석 범위를 README와 승인된 `src/ouroboros/*` 관찰 경로로 제한했다.
- [ ] 설치나 실행 검증 없이 확인할 수 없는 주장을 넣지 않았다.
- [ ] GitHub stars, 성능, 가격, 시장성 같은 변동 정보나 마케팅성 주장을 넣지 않았다.
- [ ] 순서, 상태, 재시도, 종료 판단을 각각 한 번 이상 표시했다.
- [ ] 내 업무용 하네스에 가져갈 원칙과 그대로 복사하지 않을 것을 구분했다.
- [ ] 새로운 외부 사례, 검증되지 않은 수치, 가격 또는 마케팅성 정보를 넣지 않았다.
