# 2004-4-3 Codex Workspace

이 폴더는 `2004-4-2-task-breakdown-blueprint.md`의 Agent Execution Graph를 Codex에서 돌릴 수 있게 옮긴 작업분해 하네스 v1이다.

메인 세션이 상태를 소유하고, 각 sub agent는 리프 하나만 맡아 handoff 한 장을 돌려준다.

## Node / Edge

```text
START
  |
  | input
  |   ../2004-3-3-workspace/meeting.md
  v
+------------------------------------------------+
| L1 결정 항목 추출 질문                          |
| sub agent: ask                                 |
| output: 질문 4개 + 대상자/팀 + 근거 + 판정       |
+------------------------------------------------+
  |
  | pass
  v
+------------------------------------------------+
| MAIN INLINE BUILD                              |
| sub agent 아님                                  |
| action: L3 6칸 맵핑 + L4 확인 필요 항목 구성     |
| output: 1page 초안 7칸 + 근거 줄                |
+------------------------------------------------+
  |
  | same draft goes to parallel review
  v
        +----------------------------------------+
        |                                        |
        v                                        v
+-------------------------------+      +-------------------------------+
| L5 보존 표현 인용 검사          |      | L6 도메인 제약 위반 검사       |
| sub agent: review              |      | sub agent: review              |
| output: 인용 위반 verdict       |      | 제약 위반 verdict              |
+-------------------------------+      +-------------------------------+
        |                                        |
        +------------------+---------------------+
                           |
                           v
                  +----------------+
                  | MERGE          |
                  | L5 + L6 verdict|
                  +----------------+
                           |
             +-------------+-------------+
             |                           |
             v                           v
        final output              route back / exit
```

## Route Loop

```text
for each leaf:
  main session
    |
    | 1. handoff brief
    v
  disposable sub agent
    |
    | 2. handoff result
    v
  main session
    |
    | 3. review gate
    |    - format
    |    - evidence
    |    - false-negative correction
    v
  route
    |- pass     -> next leaf / MERGE
    |- rework   -> same sub agent with narrowed correction brief
    |- ask-back -> L1 ask sub agent or human decision owner
    `- exit     -> preserve state and report blocker
```

## Handoff Shape

모든 sub agent는 같은 handoff 골격으로 메인 세션과 주고받는다.

```text
MAIN SESSION
  sends:
    # Handoff Brief
    - leaf name
    - purpose
    - input
    - scope / do-not-do
    - forbidden claims
    - done condition
    - evidence source

SUB AGENT
  returns:
    # Handoff — <leaf> · <agent type> 세션 -> 메인
    ## From task
    - 목적
    - 입력
    - 범위
    - 금지
    - 완료조건

    ## Result
    - 결과 요약
    - 판정
    - 남은 질문
    - 다음 세션의 첫 행동
```

## Leaf Handoffs

```text
L1 결정 항목 추출 질문
  main -> ask sub agent
    input:
      - meeting.md
      - 미결정 기준: 결정권자 / 일정 / 수치 출처 / 환불 기준
    request:
      - 질문 4개를 만든다
      - 각 질문에 대상자/팀과 근거를 붙인다

  ask sub agent -> main
    output:
      - 질문 4개
      - 대상자/팀
      - 원문 근거
      - 판정: 통과 / 재작업 / 재질문 / 종료
      - 다음 행동

  gate:
    format   -> 질문 4개, 대상자/팀, 판정, 다음 행동이 있는가
    evidence -> 질문이 회의록의 미결정 항목에서 나왔는가
    FN       -> 표현 차이는 살리고, 근거 없는 외부 질문은 실패시키는가
```

```text
MAIN INLINE BUILD
  main only
    input:
      - L1 handoff
      - meeting.md
    output:
      - 1page 초안 7칸
      - 보존 표현 후보 3줄
      - 각 칸의 근거 파일/줄

  note:
    L3 6칸 맵핑과 L4 확인 필요 항목은 sub agent node가 아니다.
```

```text
L5 보존 표현 인용 검사
  main -> review sub agent
    input:
      - 1page 초안 7칸
      - 보존 표현 후보 3줄
      - meeting.md 원문 줄번호
    request:
      - 큰따옴표 인용이 원문과 일치하는지 본다
      - 위반 인용과 원문 줄번호를 돌려준다

  review sub agent -> main
    output:
      - 통과 / 위반 verdict
      - 위반 인용
      - 원문 줄번호
      - 다음 행동

  gate:
    format   -> 보존 표현 3줄과 출처 줄번호가 있는가
    evidence -> 인용이 meeting.md 원문과 직접 대조되는가
    FN       -> 조사/어미 차이는 허용하고 값/문장 누락만 실패시키는가
```

```text
L6 도메인 제약 위반 검사
  main -> review sub agent
    input:
      - 1page 초안 7칸
      - L1 질문/답 상태
      - meeting.md 근거 줄
    request:
      - 외부 사실 단정
      - 미확인 수치
      - 범위 밖 항목
      - 미결정 결론 톤
      네 가지를 검사한다

  review sub agent -> main
    output:
      - 통과 / 위반 verdict
      - 위반 목록
      - 근거 줄
      - 다음 행동

  gate:
    format   -> 네 가지 도메인 제약 검사 결과가 모두 있는가
    evidence -> 각 판단이 meeting.md 줄과 연결되는가
    FN       -> 표현 차이는 허용하되 값 단정은 실패시키는가
```

## Current Dry-run

```text
L1 결정 항목 추출 질문
  -> sub agent handoff saved:
     handoffs/dry-run-L1-handoff.md

  -> review hook:
     python3 .codex/hooks/review_gate.py handoffs/dry-run-L1-handoff.md --leaf L1

  -> result:
     route: pass
     failures: none
     next_action: move to next leaf or merge gate
```

## SessionStart Hook

```text
.codex/hooks.json
  |- hooks.SessionStart
  |    matcher: startup|resume|clear|compact
  |    command: python3 /Users/jaegyu.lee/Project/fast/practice-materials/chapter-04/codex_workspace_2004-4-3/.codex/hooks/session_start.py
  |
  `- hooks.PostToolUse
       matcher: Edit|Write|apply_patch
       command: python3 /Users/jaegyu.lee/Project/fast/practice-materials/chapter-04/codex_workspace_2004-4-3/.codex/hooks/review_gate.py
```

When Codex starts in this workspace, the start hook prints the harness rules:

```text
session start
  -> writes state/session-start-context.md
  -> exits 0 without stdout
```

Project-local hooks require the workspace `.codex` layer to be trusted. In Codex CLI, use `/hooks` if the hook appears as needing review. SessionStart intentionally writes context to a file instead of printing JSON, because invalid stdout JSON makes Codex reject the hook.

## Codex Custom Agents

```text
.codex/agents/
  |- ask.toml
  |    role: L1 결정 항목 추출 질문
  |    output: handoffs/handoff-L1-ask.md
  |
  |- build.toml
  |    role: optional L3+L4 7칸 초안 build
  |    output: handoffs/handoff-L3L4-build.md
  |
  `- review.toml
       role: L5/L6 review gate judge
       output: handoffs/handoff-<leaf>-review.md
```

The agent files follow Codex custom agent TOML shape: `name`, `description`, and `developer_instructions`.

## Files

```text
.
|- Agent.md
|- README.md
|- harness-v1.md
|- .codex/
|  |- README.md
|  |- config.toml
|  |- hooks.json
|  |- agents/
|  |  |- ask.toml
|  |  |- build.toml
|  |  `- review.toml
|  `- hooks/
|     |- session_start.py
|     `- review_gate.py
|- inputs/
|  |- ac-tree.md
|  |- agent-execution-graph.md
|  |- handoff-L3-result.md
|  |- handoff-template.md
|  |- meeting.md
|  `- review-gate.md
|- handoffs/
|  `- dry-run-L1-handoff.md
`- state/
   |- run-log.md
   `- harness-state.md
```
