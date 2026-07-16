# 2004-4-3. [프로젝트 2] 내 그래프대로 sub agent를 실제로 돌리는 하네스 v1

## 이 산출물

4-4-2에서 그린 Agent Execution Graph를, 메인 세션이 리프마다 sub agent를 띄워 실제로 돌리는 protocol로 옮긴 작업분해 하네스 v1. 메인 세션은 상태판, sub agent는 소모품이다. 메인이 handoff로 brief를 보내고, sub agent가 handoff로 결과를 돌려주고, review gate가 통과를 판정하고, route로 다음 리프를 정한다. 이 v1이 다루지 못하는 장기 상태·checkpoint·재현 가능한 결정론 gate는 5장 Ouroboros에서 다룬다.

## 목적

이 프로젝트는 "좋은 프롬프트 한 개"를 만드는 게 아니라, 내 업무를 sub agent 여러 명으로 분업해 같은 흐름으로 돌리는 하네스를 만든다. 같은 입력을 넣으면 리프마다 handoff·verdict·다음 행동이 같은 구조로 나와야 한다. dry-run에서는 진짜 sub agent를 한 번 띄워 handoff → review → route가 도는지 직접 본다.

## 권장 시간

40분

## 준비물

- 2004-4-2에서 그린 Agent Execution Graph
- 2004-2-3 handoff 양식 v1
- 2004-3-3 review gate
- dry-run에 쓸 내 실제 업무 입력 한 건 (예: 회의록 원문)

## 입력

| 항목 | 내용 |
|---|---|
| 하네스 이름 |  |
| 내 업무 입력 위치 |  |
| node = 리프 목록 (이름 그대로) |  |
| 리프별 담당 sub agent |  |
| sub agent를 띄우는 방법 (내 도구에서) |  |
| dry-run으로 돌릴 리프 1개 |  |

## AI에게 시키기

위 입력을 채워 붙인 뒤, 한 줄을 덧붙인다.

> 이 입력으로 작업분해 하네스 v1을 만들어줘. 메인 세션이 리프마다 sub agent를 띄워 handoff로 보내고, handoff로 받고, review gate(format·evidence·FN 보정)로 통과시키고, pass/rework/ask-back/exit로 route하는 루프로 써. sub agent를 띄우는 방법은 지금 내 도구 기준 예시로 적되, 루프 자체는 도구가 바뀌어도 그대로이게 해. 마지막에 dry-run 한 번과 5장으로 넘길 state/checkpoint 질문을 남겨.

## 단계별 진행

1. **contract 고정** — 내 업무 input, 검수까지 통과한 산출물 output, pass/exit 조건을 한 줄씩 적는다.
2. **메인 루프 정의** — 리프마다 같은 네 단계를 돈다: handoff로 brief 보내기 → sub agent가 handoff로 회수 → review gate로 통과 → route.
3. **sub agent 띄우는 법** — 지금 도구에서 어떻게 띄울지 예시로 적는다 (예: Claude Code Task, Codex sub-agent, 새 대화창). 루프는 도구와 분리해 남긴다.
4. **route 연결** — pass·rework·ask-back·exit가 각각 어느 리프·세션으로 가는지 첫 행동까지 붙인다.
5. **dry-run** — 내 리프 하나에 진짜 sub agent를 한 번 띄운다. handoff를 받고 review gate에 통과시켜 verdict와 다음 행동을 남긴다.
6. **v2 backlog** — 상태 저장, checkpoint, 재현 가능한 결정론 gate, MCP 후보를 5장 질문으로 남긴다.

## 작성 템플릿

````markdown
# Task Decomposition Harness v1 — <하네스 이름>

## 1. Harness Contract
- input: 내 업무 입력
- output: 검수까지 통과한 산출물 + 리프마다 verdict
- pass condition: 리프마다 handoff·근거·verdict·다음 행동이 있다
- exit condition: 같은 rework 반복 / 외부 결정 대기 / 입력 부족

## 2. 메인 루프 (리프마다 반복)
1. handoff 양식으로 sub agent에 brief를 보낸다
2. sub agent가 handoff 한 장으로 결과를 돌려준다
3. review gate(format·evidence·FN 보정)로 통과시킨다
4. route: pass=다음 리프 / rework=같은 sub agent에 좁힌 재의뢰 / ask-back=ask sub agent / exit=사람 보고

## 3. Runtime Surface (예시 — 바뀌어도 루프는 그대로)
| 항목 | 지금 내 도구에서 | 도구와 무관하게 남길 것 |
|---|---|---|
| sub agent 띄우기 |  | node 실행 단위 |
| guard |  | format·evidence·FN |
| handoff |  | node 입력·출력 계약 |
| 상태 메모 |  | 리프별 verdict + route |

## 4. Dry-run (sub agent 1회 실제 실행)
| 항목 | 결과 |
|---|---|
| 돌린 리프 |  |
| 띄운 sub agent |  |
| handoff 회수 |  |
| review verdict |  |
| route + 다음 행동 |  |

## 5. v2 Backlog (5장으로)
- 여러 세션에 걸쳐 남겨야 할 상태:
- 실패 시 돌아갈 checkpoint:
- 같은 입력에 같은 verdict를 보장할 결정론 gate:
- MCP로 올릴 후보:
````

## 예시 — meeting-to-1page 분업 dry-run

````markdown
# Task Decomposition Harness v1 — meeting-to-1page

## 4. Dry-run (sub agent 1회 실제 실행)
- 요청: 회의록(sample-meeting.md)으로 1page 본문을 채우고 검수까지 돌려줘
| 항목 | 결과 |
|---|---|
| 돌린 리프 | 본문 채우기 (build) |
| 띄운 sub agent | build 세션 (새 대화창) |
| handoff 회수 | 1page 본문 6칸 + 근거(출처) |
| review verdict | evidence 1건 실패 — 성공 기준 "60%"가 근거 줄에 없음 |
| route + 다음 행동 | rework — build 세션에 "60% 근거 줄을 달거나 본문에서 빼기"만 재의뢰 |
````

## 완료 기준

- 하네스가 내 업무 입력에서 시작해 리프마다 같은 루프(handoff → review → route)를 돈다.
- node가 일반명이 아니라 내 실제 리프 이름이다.
- dry-run에서 sub agent를 진짜 한 번 띄워 verdict와 다음 행동이 남았다.
- sub agent 띄우는 방법(예시)과 루프(도구 무관)가 분리되어 있다.
- 5장으로 넘길 상태·checkpoint·결정론 gate 질문이 한 줄 이상 남아 있다.

## 자기 검수 체크리스트

- [ ] 메인 루프 네 단계가 모든 리프에 똑같이 적용된다.
- [ ] handoff는 4-2 양식, guard는 4-3 review gate를 그대로 쓴다.
- [ ] dry-run이 멋진 산출물이 아니라 흐름(verdict·route)을 남긴다.
- [ ] sub agent를 띄우는 도구가 바뀌어도 루프는 그대로다.
- [ ] 같은 입력을 두 번 넣으면 같은 구조의 결과가 나온다.

---

# Runtime Surface 적용 가이드 — Claude Code / Codex

4-2의 Agent Execution Graph는 도구와 무관한 설계다. 4-3 하네스에서는 그 그래프를 실제 런타임 표면에
옮긴다. 도구가 Claude Code든 Codex든 바뀌지 않는 것은 하나다.

```text
handoff brief -> sub agent handoff result -> review gate -> route
```

도구별로 바뀌는 것은 sub agent를 띄우는 표면, hook 등록 위치, custom agent 정의 파일뿐이다.

## 1. 공통 실행 구조

```text
workspace/
|- README.md
|- harness-v1.md 또는 task-decomposition-harness-v1.md
|- inputs/
|  |- ac-tree.md
|  |- agent-execution-graph.md
|  |- handoff-template.md
|  |- review-gate.md
|  |- meeting.md
|  `- handoff-L3-result.md
|- handoffs/
|  `- <sub agent가 돌려준 handoff>.md
`- state/
   `- run-log.md
```

`handoffs/`에는 sub agent가 돌려준 원문을 남긴다. `state/run-log.md`에는 leaf, agent, handoff path,
verdict, route, next action을 한 줄로 남긴다.

## 2. Claude Code로 실행할 경우

Claude Code 참조 워크스페이스:

```text
practice-materials/chapter-04/claude_workspace_2004-4-3/
```

### 2.1 Claude Code workspace 표면

```text
claude_workspace_2004-4-3/
|- CLAUDE.md
|- README.md
|- task-decomposition-harness-v1.md
|- inputs/
|- handoffs/
|- state/
`- .claude/
   |- settings.json
   |- hooks/
   |  |- session_start.py
   |  `- review_gate.py
   |- agents/
   |  |- ask.md
   |  |- build.md
   |  `- review.md
   `- skills/
      `- using-task-harness/
         `- SKILL.md
```

### 2.2 Claude Code hooks

`.claude/settings.json`에 hook 두 개를 등록한다.

```json
{
  "hooks": {
    "SessionStart": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "python3 \"$CLAUDE_PROJECT_DIR/.claude/hooks/session_start.py\""
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "Write|Edit",
        "hooks": [
          {
            "type": "command",
            "command": "python3 \"$CLAUDE_PROJECT_DIR/.claude/hooks/review_gate.py\""
          }
        ]
      }
    ]
  }
}
```

| hook | 트리거 | 역할 |
|---|---|---|
| `session_start.py` | 세션 시작, resume, `/clear`, compact | 하네스 규약을 `additionalContext`로 주입 |
| `review_gate.py` | `Write|Edit` 직후 | `handoffs/*.md`의 format, `meeting.md:NN` 줄 실재, `60%` 단정을 사전 검수 |

### 2.3 Claude Code sub agent

`.claude/agents/{ask,build,review}.md`로 리프별 sub agent를 둔다.

| 리프 | subagent_type | 파일 | guard |
|---|---|---|---|
| L1 결정 항목 추출 질문 | `ask` | `.claude/agents/ask.md` | format |
| L3+L4 6칸 맵핑 + 확인 필요 항목 | `build` | `.claude/agents/build.md` | format |
| L5 보존 표현 인용 검사 | `review` | `.claude/agents/review.md` | evidence |
| L6 도메인 제약 위반 검사 | `review` | `.claude/agents/review.md` | false-negative |

dispatch 예시:

```text
Task(subagent_type="review", prompt="""
L6 도메인 제약 위반 검사를 맡아라.
검수 대상: inputs/handoff-L3-result.md
근거 원문: inputs/meeting.md
gate 단계: inputs/review-gate.md
verdict와 근거 줄, 다음 행동을 handoffs/handoff-L6-review.md 에 handoff로 써라.
""")
```

## 3. Codex로 실행할 경우

Codex 참조 워크스페이스:

```text
practice-materials/chapter-04/codex_workspace_2004-4-3/
```

### 3.1 Codex workspace 표면

```text
codex_workspace_2004-4-3/
|- Agent.md
|- README.md
|- harness-v1.md
|- inputs/
|- handoffs/
|- state/
`- .codex/
   |- config.toml
   |- hooks.json
   |- hooks/
   |  |- session_start.py
   |  `- review_gate.py
   |- agents/
   |  |- ask.toml
   |  |- build.toml
   |  `- review.toml
   `- skills/
      `- using-task-harness/
         `- SKILL.md
```

### 3.2 Codex hooks

Codex는 project-local `.codex/hooks.json`에서 lifecycle hook을 읽는다. 프로젝트 `.codex` layer가
trusted 상태여야 로드된다. Codex CLI에서 hook review가 뜨면 `/hooks`로 들어가 trust한다.

```json
{
  "hooks": {
    "SessionStart": [
      {
        "matcher": "startup|resume|clear|compact",
        "hooks": [
          {
            "type": "command",
            "command": "python3 <workspace>/.codex/hooks/session_start.py",
            "timeout": 30,
            "statusMessage": "Loading 2004-4-3 harness state"
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "Edit|Write|apply_patch",
        "hooks": [
          {
            "type": "command",
            "command": "python3 <workspace>/.codex/hooks/review_gate.py",
            "timeout": 30,
            "statusMessage": "Reviewing handoff artifact"
          }
        ]
      }
    ]
  }
}
```

`SessionStart`는 stdout을 내보내지 않고 `state/session-start-context.md`에 graph, handoff,
route 규약을 기록한다. `PostToolUse`는 `handoffs/*.md` 산출물에
대해 결정론으로 볼 수 있는 슬라이스만 자동 검사한다.

```text
PostToolUse review_gate.py가 보는 것
  |- format: ## From task, ## Result, 판정, 다음 행동이 있는가
  |- evidence: meeting.md:NN 줄번호가 inputs/meeting.md에 실제로 있는가
  `- FN(value): 60%가 미확인·추정·출처 같은 단서 없이 단정됐는가

PostToolUse review_gate.py가 보지 않는 것
  |- 인용한 줄이 주장을 실제로 뒷받침하는가
  |- 성공 기준이 목표 문장인지 측정 지표인지
  `- 표현 차이와 값 누락의 의미 경계
```

의미 판정은 `review` sub agent가 맡는다. 같은 입력에 같은 verdict를 끝까지 보장하는 재현 가능한
gate는 5장 Ouroboros로 넘긴다.

### 3.3 Codex custom agents

Codex custom agent는 `.codex/agents/*.toml`로 둔다. 각 파일은 최소 `name`, `description`,
`developer_instructions`를 가진다.

```text
.codex/agents/ask.toml
  role: L1 결정 항목 추출 질문
  input: inputs/meeting.md + 결정권자/일정/수치 출처/환불 기준
  output: handoffs/handoff-L1-ask.md

.codex/agents/build.toml
  role: L3+L4 6칸 맵핑 + 확인 필요 항목
  note: 보통 MAIN INLINE BUILD. 회의록이 길 때만 sub agent로 띄운다.
  output: handoffs/handoff-L3L4-build.md

.codex/agents/review.toml
  role: L5 보존 표현 인용 검사 또는 L6 도메인 제약 위반 검사
  input: build handoff + inputs/meeting.md + inputs/review-gate.md
  output: handoffs/handoff-<leaf>-review.md
```

Codex sub agent를 띄울 때도 보내는 것은 handoff 한 장이다.

```text
spawn review agent with brief:
  L6 도메인 제약 위반 검사를 맡아라.
  검수 대상: inputs/handoff-L3-result.md
  근거 원문: inputs/meeting.md
  gate 단계: inputs/review-gate.md
  verdict와 근거 줄, 다음 행동을 handoffs/handoff-L6-review.md 에 handoff로 써라.
```

### 3.4 Codex 실행 루프

```text
Codex session start
  -> SessionStart hook writes state/session-start-context.md and exits 0
  -> main session reads README.md / Agent.md / harness-v1.md / state/run-log.md

for each leaf:
  main session
    -> fill handoff brief from inputs/handoff-template.md
    -> spawn ask/build/review custom agent
    -> receive handoff markdown in handoffs/
    -> PostToolUse hook checks deterministic slice
    -> main/review agent applies full review gate(format -> evidence -> FN)
    -> route pass / rework / ask-back / exit
    -> append state/run-log.md and state/harness-state.md
```

## 4. Claude / Codex 대응표

| 역할 | Claude Code | Codex |
|---|---|---|
| 세션 시작 규약 주입 | `.claude/settings.json`의 `SessionStart` | `.codex/hooks.json`의 `SessionStart` |
| 세션 시작 hook | `.claude/hooks/session_start.py` | `.codex/hooks/session_start.py` |
| handoff 사후 검수 | `.claude/settings.json`의 `PostToolUse(Write|Edit)` | `.codex/hooks.json`의 `PostToolUse(Edit|Write|apply_patch)` |
| review gate hook | `.claude/hooks/review_gate.py` | `.codex/hooks/review_gate.py` |
| ask sub agent | `.claude/agents/ask.md` | `.codex/agents/ask.toml` |
| build sub agent | `.claude/agents/build.md` | `.codex/agents/build.toml` |
| review sub agent | `.claude/agents/review.md` | `.codex/agents/review.toml` |
| 메타 스킬 | `.claude/skills/using-task-harness/SKILL.md` | `.codex/skills/using-task-harness/SKILL.md` |
| 상태판 | `state/run-log.md` | `state/run-log.md` + `state/harness-state.md` |
