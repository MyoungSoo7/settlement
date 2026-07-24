# Ouroboros — 명세 우선(spec-first) AI 코딩 워크플로 엔진

> 이 문서는 우리 저장소에 설치된 **Ouroboros 플러그인**(현재 캐시 버전 `0.50.5`, 저장소 `Q00/ouroboros`, MIT)의
> 아키텍처·구조·스킬 쓰임새·핵심 개념을 정리한 참조 문서입니다. "왜 이렇게 설계됐는가"에 초점을 둡니다.
> - 근거: 플러그인 `README.md` / `docs/architecture.md` / `.claude-plugin/SKILL_CAPABILITY_GUIDE.md` (2026-07 캐시 기준)
> - 우리 저장소에서의 실행 함정은 맨 아래 [§8 이 저장소에서의 실전 메모](#8-이-저장소에서의-실전-메모) 참조

---

## 1. 한 줄 요약 — "Stop prompting. Start specifying."

Ouroboros(우로보로스, 자기 꼬리를 무는 뱀)는 **막연한 아이디어를 검증된 코드베이스로 바꾸는 Agent OS**입니다.
핵심 주장은 **"AI 코딩의 병목은 AI 능력이 아니라 인간의 명확성(clarity)"** 이라는 것입니다. 그래서 곧바로 코드를
생성하지 않고, 먼저 **소크라테스식 인터뷰로 숨은 가정을 드러내고 → 불변(immutable) 명세(Seed)로 의도를 고정한 뒤
→ 실행하고 → 3단 게이트로 검증하고 → 진화**시킵니다. 이 다섯 단계가 뱀처럼 자기 꼬리를 물고 반복(loop)됩니다.

```
    Interview → Seed → Execute → Evaluate
        ^                           |
        +---- Evolutionary Loop ----+
```

한 번 돌 때마다 **반복이 아니라 진화**합니다. 평가의 산출물이 다음 세대 Seed의 입력이 되어, 시스템이 자신이 무엇을
만드는지 진짜로 알게 될 때까지(=온톨로지가 안정될 때까지) 스스로를 질문합니다.

---

## 2. 왜 필요한가 — 해결하려는 문제

인간의 요구사항은 늘 **모호하고(ambiguous)·불완전하고(incomplete)·모순적이고(contradictory)·표면적(surface-level)**
입니다. 이런 입력을 AI가 그대로 실행하면 GIGO(Garbage In, Garbage Out)가 됩니다.

| 문제 | 바닐라 AI 코딩에서 벌어지는 일 | Ouroboros의 처방 |
| :--- | :--- | :--- |
| 막연한 프롬프트 | AI가 의도를 추측 → 재작업 반복 | 소크라테스 인터뷰가 코드 이전에 가정을 강제로 노출 |
| 명세 부재 | 빌드 도중 아키텍처가 드리프트 | 불변 Seed가 의도를 잠금, 모호도 게이트(≤0.2)가 조기 코딩을 차단 |
| 수동 QA | "좋아 보인다"는 검증이 아님 | 3단 자동 게이트: Mechanical → Semantic → Multi-Model Consensus |

Ouroboros는 두 가지 고전적 방법을 코드 워크플로에 이식합니다.
1. **소크라테스식 질문(Socratic Questioning)** — 숨은 가정을 드러내고 모순을 노출하고 당연함에 도전
2. **온톨로지 분석(Ontological Analysis)** — 증상이 아니라 근본 문제를, 우연이 아니라 본질을 찾음

> 철학적 엔진: *Wonder("무엇을 원하는가?") → Ontology("내가 원하는 그것은 대체 무엇인가?")*
> "할 일 CLI를 만들어줘"가 아니라 "**할 일이란 무엇인가?** 삭제 가능한가 보관 가능한가? 혼자 쓰나 팀이 쓰나?"를
> 먼저 답하면 재작업 한 무더기를 통째로 제거할 수 있습니다. **온톨로지 질문이 가장 실용적인 질문입니다.**

---

## 3. Agent OS 스택 — 3계층/3저장소

Ouroboros는 OS처럼 **안정적인 커널(OS)** · **도메인 워크플로(Apps)** · **사람이 앉는 셸(Shell)** 로 나뉩니다.

| 계층 | 저장소 | 역할 |
| :--- | :--- | :--- |
| **Shell** (터미널 클라이언트) | `Q00/ourocode` | Claude/Codex/Gemini CLI를 한 세션에서 구동하는 네이티브 TUI |
| **Apps** (도메인 워크플로) | `Q00/ouroboros-plugins` | UserLevel 플러그인 계약 — 코어 프리미티브를 조합한 설치형 도메인 프로그램(PR ops, Jira sync 등) |
| **OS** (코어) | `Q00/ouroboros` | Agent OS 커널 — Seed·Ledger·Runtime·MCP·안전경계. **우리가 설치한 것이 이것** |

```
  ourocode  ──►  ooo / ouroboros-plugins  ──►  ouroboros core (Seed · Ledger · MCP · Runtime)
   shell             user-level apps                        kernel
```

**커널의 핵심 계약**: 어떤 LLM이 실행하든, 모든 행동은 **Seed에 묶이고(Seed-bound)·원장에 기록되고(ledger-recorded)·
재생 가능한(replayable) 이벤트**가 됩니다. 비결정적 에이전트 작업을 재생 가능·관측 가능·정책 구속(policy-bound)
실행 계약으로 바꾸는 것이 Ouroboros가 파는 값어치입니다.

---

## 4. 내부 아키텍처 — 6계층 · 6단계 파이프라인

### 4.1 코어 6계층 (아키텍처 문서 Section 1–6)

| 계층 | 책임 |
| :--- | :--- |
| **① Skills & Agents Registry** | 코어에 번들된 스킬(14종)·에이전트(9종) 자동 발견. `ooo`(=`/ouroboros:`) 매직 프리픽스 감지, 핫리로드 |
| **② Core Layer** | 불변 데이터 모델 — Seed(frozen Pydantic), Acceptance Criteria Tree(MECE 재귀 분해), Ontology 스키마, 버전·모호도 스코어 |
| **③ Execution Layer** | 자기참조 지속 루프 + 의존성 인지 병렬 실행 + 자동 스케일·복원력 |
| **④ State Layer** | 이벤트 소싱 — SQLite append-only 이벤트 스토어, 완전 리플레이, 체크포인트(압축) |
| **⑤ Orchestration Layer** | 6단계 파이프라인 + PAL 라우터(비용 최적화) |
| **⑥ Presentation Layer** | Textual 기반 TUI 대시보드(AC 트리·에이전트 활동·비용·드리프트 실시간) + Typer CLI |

> 그 위에 **⑦ UserLevel Programs Layer**(설치형 플러그인)가 있으나 3rd-party 설치 표면(`ooo plugin add`)은
> 아직 `main` 미구현(설계 목표). 지금 쓰는 first-party 프로그램은 `ooo auto`·`ooo run`·`ooo pm` 뿐입니다.

### 4.2 6단계 파이프라인

```
Phase 0: BIG BANG        → 요구사항을 Seed로 결정화 (Interview → Seed)
Phase 1: PAL ROUTER      → 태스크 복잡도에 맞는 모델 티어 선택
Phase 2: DOUBLE DIAMOND  → AC 분해 후 실행 (Discover → Define → Design → Deliver)
Phase 3: RESILIENCE      → 정체(stagnation) 감지 시 측면적 사고로 돌파
Phase 4: EVALUATION      → 3단 게이트로 산출물 검증
Phase 5: SECONDARY LOOP  → 미뤄둔 TODO 처리 후 필요 시 순환 복귀 ↺
```

**Phase 0 — Big Bang (Interview → Seed).**
소크라테스 인터뷰로 최대 라운드까지 질문하며 매 응답 후 **모호도 스코어(Ambiguity)** 를 재계산합니다.
`Ambiguity ≤ 0.2` 가 되어야 비로소 불변 Seed가 자동 생성됩니다. (대부분의 사용자는 Seed를 손으로 쓸 필요 없음 —
인터뷰가 자동 결정화)

**Phase 1 — PAL Router (Progressive Adaptive LLM).** 비용 최적화의 핵심.

| 티어 | 비용 | 복잡도 임계 |
| :--- | :--- | :--- |
| FRUGAL | 1x | < 0.4 |
| STANDARD | 10x | < 0.7 |
| FRONTIER | 30x | ≥ 0.7 또는 critical |

전략은 **"싼 것부터, 실패할 때만 승급(escalate)"**. 복잡도 = `0.30·토큰 + 0.30·툴의존 + 0.40·AC깊이`.
연속 2회 실패 시 승급(Frugal→Standard→Frontier→정체 이벤트), 연속 5회 성공 시 강등. 유사 태스크(Jaccard ≥ 0.80)는
성공 티어를 상속.

**Phase 2 — 실행(재귀 분해).**
각 AC는 기본 **atomic**. 프로파일 축을 따라 진짜로 여러 독립 검증 단위에 걸칠 때만 2–5개 sub-AC로 분해(분해는
sub-AC 하나당 에이전트 세션 하나 비용이라 **보수적**). 기본 깊이 캡 `DEFAULT_MAX_DECOMPOSITION_DEPTH=2`,
실패는 "더 깊이 쪼개기"가 아니라 **시도-후-바운스(bounded retry + 평가 피드백)** 로 처리.

**Phase 3 — Resilience (측면적 사고).**
정체 4패턴을 stateless로 감지 → 5개 페르소나가 "해답"이 아니라 "사고 프롬프트"를 생성.

| 정체 패턴 | 감지 | | 페르소나 | 전략 |
| :--- | :--- | :--- | :--- | :--- |
| SPINNING | 같은 출력 해시 반복 | | HACKER | 비관습적 우회 |
| OSCILLATION | A→B→A→B 진동 | | RESEARCHER | 정보를 더 수집 |
| NO_DRIFT | 드리프트 불변(ε<0.01) | | SIMPLIFIER | 복잡도 축소 |
| DIMINISHING_RETURNS | 개선율 < 0.01 | | ARCHITECT | 근본 재구조화 |
| | | | CONTRARIAN | 모든 가정에 도전(전 패턴) |

**Phase 4 — Evaluation (3단 게이트).** 싼 검사부터, 비싼 합의는 게이트에서만.

1. **Mechanical ($0)** — lint/build/test/정적분석/커버리지(임계 70%). 마커 파일로 언어 자동감지
   (`uv.lock`→Python, `Cargo.toml`→Rust, `go.mod`→Go, `package-lock.json`→Node…). 하나라도 실패하면 즉시 중단.
2. **Semantic ($$)** — AC 준수·목표 정합·드리프트·불확실성 스코어링. `≥0.8` & 트리거 없으면 합의 없이 승인.
3. **Consensus ($$$)** — 아래 6개 트리거 중 하나가 걸릴 때만 다중 모델 투표. Simple(3모델 2/3 다수결) 또는
   Deliberative(Advocate/Devil's Advocate/Judge 역할 + 온톨로지 질문).

> **합의 트리거 6종**(우선순위): ① Seed 수정(불변이라 어떤 변경이든 합의 필요) ② 온톨로지 진화 ③ 목표 재해석
> ④ Seed 드리프트 > 0.3 ⑤ Stage2 불확실성 > 0.3 ⑥ 측면적 사고 채택.

---

## 5. 핵심 개념 — 여기가 가장 중요합니다

Ouroboros를 한 문장으로 이해한다면 다음 **두 개의 수학적 게이트 + 하나의 철학**입니다.

### 5.1 Seed — 워크플로의 "헌법"

**불변(frozen) 명세**입니다. Goal / Constraints / Acceptance Criteria / Ontology Schema / Exit Conditions로 구성되며,
인터뷰가 자동 생성한 뒤에는 **절대 수정 불가**. 방향(의도)은 못 바꾸고, 그 방향에 도달하는 경로만 적응합니다
(설계 원칙 "Immutable Direction"). 이것이 빌드 도중 아키텍처 드리프트를 원천 차단합니다.

### 5.2 게이트 A — Ambiguity ≤ 0.2 (Wonder와 Code 사이의 관문)

인터뷰는 "준비된 느낌"이 아니라 **수학이 준비됐다고 말할 때** 끝납니다.

```
Ambiguity = 1 − Σ(clarity_i × weight_i)
```

각 차원을 LLM이 0.0–1.0으로 채점(temperature 0.1로 재현성 확보) 후 가중합. **80% 가중 명확도**를 넘어야
남은 미지가 코드 레벨에서 해소될 만큼 작다고 보고 Seed 생성을 허용합니다.

| 차원 | Greenfield | Brownfield |
| :--- | :--: | :--: |
| Goal Clarity (목표가 구체적인가) | 40% | 35% |
| Constraint Clarity (제약이 정의됐나) | 30% | 25% |
| Success Criteria (결과가 측정 가능한가) | 30% | 25% |
| Context Clarity (기존 코드베이스 이해) | — | 15% |

### 5.3 게이트 B — Ontology Similarity ≥ 0.95 (뱀이 멈추는 지점)

진화 루프는 영원히 돌지 않습니다. **연속 세대가 온톨로지적으로 동일한 스키마**를 낼 때 수렴·정지합니다.

```
Similarity = 0.5·이름중복 + 0.3·타입일치 + 0.2·완전일치
```

`≥ 0.95` 면 수렴. 추가로 병리적 패턴도 감지: 정체(3세대 연속 ≥0.95), 진동(주기-2 사이클), 반복 질문(3세대 70%
이상 질문 중복), 하드캡(30세대). 예:

```
Gen 1: {Task, Priority, Status}
Gen 2: {Task, Priority, Status, DueDate}  → similarity 0.78 → CONTINUE
Gen 3: {Task, Priority, Status, DueDate}  → similarity 1.00 → CONVERGED (정지)
```

> **두 게이트, 하나의 철학**: *명확해지기 전엔 만들지 말고(Ambiguity ≤ 0.2), 안정되기 전엔 진화를 멈추지 마라
> (Similarity ≥ 0.95).*

### 5.4 이벤트 소싱 + 드리프트 — 재생 가능성의 토대

모든 상태 변화는 단일 `events` 테이블(SQLite, append-only, dot-notation 과거형 이벤트 타입)에 불변 이벤트로
적재됩니다. → 완전 감사추적 · 체크포인트 복구(3단 롤백, 5분 주기) · 세션 재개 · 회고 분석. 그래서 **Ralph 루프가
stateless**일 수 있습니다: EventStore가 전체 계보(lineage)를 재구성하므로 머신이 재시작해도 뱀은 멈춘 곳부터 이어갑니다.

**Drift**(원래 Seed에서 얼마나 벗어났나) = `Goal 50% + Constraint 30% + Ontology 20%`, 임계 ≤ 0.3. 높으면 Seed 재검토 트리거.

### 5.5 Nine Minds — 아홉 개의 사고 모드(에이전트)

온디맨드로만 로드되는 9개 에이전트. 각자 하나의 질문을 담당합니다.

| 에이전트 | 핵심 질문 |
| :--- | :--- |
| Socratic Interviewer | "너는 무엇을 가정하고 있나?" (질문만, 절대 안 만듦) |
| Ontologist | "이건 진짜 무엇인가?" |
| Seed Architect | "이 명세는 완전하고 모호하지 않은가?" |
| Evaluator | "우리가 옳은 것을 만들었나?" |
| Contrarian | "반대가 참이라면?" |
| Hacker | "실제로 진짜인 제약은 무엇인가?" |
| Simplifier | "동작할 가장 단순한 것은?" |
| Researcher | "우리가 실제로 가진 증거는?" |
| Architect | "다시 시작한다면 이렇게 지을까?" |

---

## 6. 스킬(`ooo`)의 쓰임새

Claude Code 세션 안에서는 `ooo <cmd>` 스킬로, 터미널에서는 `ouroboros` CLI로 호출합니다.
(우리 세션에서 실제 노출되는 이름은 `ouroboros:<skill>` 및 `mcp__plugin_ouroboros_ouroboros__ouroboros_*` MCP 도구)

| 스킬 | CLI 대응 | 쓰임새 |
| :--- | :--- | :--- |
| `ooo setup` | `ouroboros setup` | 런타임 등록·프로젝트 구성(1회성) |
| `ooo interview` | `ouroboros init start` | **소크라테스 인터뷰** — 숨은 가정 노출, 모호도 게이트까지 |
| `ooo auto` | `ouroboros auto` | 목표 → A등급 Seed → 실행 핸드오프를 bounded loop로 자동 진행 |
| `ooo seed` | *(인터뷰가 생성)* | 불변 명세로 결정화 |
| `ooo run` | `ouroboros run seed.yaml` | Double Diamond 분해로 실행 |
| `ooo evaluate` | *(MCP)* | 3단 검증 게이트 |
| `ooo evolve` | *(MCP)* | 온톨로지 수렴까지 진화 루프 |
| `ooo unstuck` | *(MCP)* | 막혔을 때 5개 측면적 사고 페르소나 투입 |
| `ooo status` | `ouroboros status executions` | 세션 추적 + (MCP) 드리프트 감지 |
| `ooo resume-session` | `ouroboros resume` | 진행 중 세션 재부착 (`/resume`은 Claude Code 예약어라 이 이름 사용) |
| `ooo cancel` | `ouroboros cancel execution` | 멈춘/고아 실행 취소 |
| `ooo ralph` | *(MCP)* | **검증될 때까지 세션 경계를 넘어 지속되는 루프** |
| `ooo pm` | *(MCP)* | PM 관점 인터뷰 + PRD 생성 |
| `ooo qa` | *(스킬)* | 임의 산출물에 대한 범용 QA 판정 |
| `ooo brownfield` | *(스킬)* | 기존(brownfield) 저장소/워크트리 기본값 스캔·관리 |
| `ooo publish` | *(gh CLI)* | Seed를 GitHub Epic/Task 이슈로 발행(팀 워크플로) |
| `ooo tutorial` / `ooo help` / `ooo update` | — | 인터랙티브 학습 / 레퍼런스 / 업데이트 |

**전형적 흐름**: `interview`(막연함→모호도≤0.2 Seed) → `run`/`auto`(실행) → `evaluate`(3단 게이트) →
`evolve`/`ralph`(수렴까지) → 막히면 `unstuck`, 꼬이면 `status`·`cancel`·`resume-session`.

> **SKILL_CAPABILITY_GUIDE의 핵심 규율(Claude 런타임)**: 스킬이 `orchestrate_subagents`를 요구하고 MCP 응답에
> `dispatch_mode=host_driven` / `host_action=spawn_subagents`(예: `question_advisory_subagents`)가 찍혀 오면,
> **payload당 Task/Agent 서브에이전트 하나씩 한 배치로 스폰**하고 `result_correlation_key`로 결과를 상관시킨 뒤
> 부모 세션에서 합성합니다. 비가역 전이(예: seed 생성) 전에는 목표를 재진술하고 명시적 승인을 받습니다.

---

## 7. 런타임 추상화 + MCP — "같은 명세, 다른 실행 엔진"

Ouroboros는 워크플로 오케스트레이션을 **실행 런타임과 분리**합니다. 코어(이벤트 소싱·6단계·평가)는 그대로 두고,
`AgentRuntime` 프로토콜(`execute_task()` 스트리밍 + `execute_task_to_result()`)만 구현하면 어떤 AI 코딩 도구든
백엔드가 됩니다. 오케스트레이터는 백엔드 내부를 절대 들여다보지 않고 정규화된 `AgentMessage`/`RuntimeHandle`/`TaskResult`
타입으로만 대화합니다.

**출하된 어댑터**: Claude Code(`claude`) · Codex CLI(`codex`) · OpenCode · Hermes · Gemini CLI · Kiro · GitHub Copilot
CLI · Pi. `create_agent_runtime()`이 `OUROBOROS_AGENT_RUNTIME` 환경변수 → `config.yaml` → 명시 `backend=` 순으로 해석.

**MCP는 양방향 허브**:
- Server 모드(`ouroboros mcp serve`) — `ouroboros_execute_seed`·`ouroboros_session_status`·`ouroboros_query_events` 등을
  Claude Desktop 등에 노출
- Client 모드(`ouroboros run --mcp-config`) — 외부 MCP 서버(filesystem·GitHub·DB…) 도구를 내장 도구와 병합
  (충돌 시 내장 우선, MCP끼리는 config 첫 서버 우선)

모든 LLM 호출은 LiteLLM(100+ 모델)을 통과 — provider 추상화·자동 재시도·비용 추적·스트리밍.

---

## 8. 이 저장소에서의 실전 메모

- **설치 상태**: 플러그인 캐시에 `0.50.3`·`0.50.5` 두 버전 존재(현행 `0.50.5`). 세션에는 ouroboros MCP 도구
  (`mcp__plugin_ouroboros_ouroboros__ouroboros_*`)와 `ouroboros:*` 스킬이 노출됩니다.
- **Windows 실행 함정(과거 관측)**: Windows 네이티브 경로에서 실행이 막혔던 이력이 있고 WSL 경로로는 구축에
  성공했습니다. 시드에 `execution_mode: legacy`가 필요했던 케이스가 있으니, 신버전에서 재검증한 뒤 사용하세요.
  (게이트 GREEN 판정 시 통합테스트 skip=0 확인 필수)
- **도구 발견(deferred)**: ouroboros MCP 도구는 종종 **deferred** 상태라 이름만 보이고 스키마가 안 실립니다.
  호출 전에 `ToolSearch`로 `+ouroboros <skill>`(예: `+ouroboros evaluate`)을 돌려 스키마를 로드해야 합니다.
  deferred는 턴 사이에 다시 언로드될 수 있어 **호출 직전 재발견**이 안전합니다.
- **우리 가드레일과의 관계**: Ouroboros는 명세·실행 오케스트레이터일 뿐, 우리 저장소의 회계·MSA·헥사고날
  가드레일(`scripts/harness/guard.mjs` 3중 강제)을 우회하지 않습니다. Ouroboros가 생성한 코드도 동일하게
  PreToolUse·pre-commit·CI 가드를 통과해야 합니다.

---

## 9. 설계 원칙 (요약)

1. **Frugal First** — 가장 싼 옵션부터, 필요할 때만 승급
2. **Immutable Direction** — Seed(방향)는 불변, 경로만 적응
3. **Progressive Verification** — 싼 검사 먼저, 비싼 합의는 게이트에서만
4. **Lateral Over Vertical** — 막히면 더 세게 밀지 말고 관점을 바꿔라
5. **Event-Sourced** — 모든 상태 변화는 이벤트, 아무것도 잃지 않는다

> *"시작이 곧 끝이고, 끝이 곧 시작이다. 뱀은 반복하지 않는다 — 진화한다."*
