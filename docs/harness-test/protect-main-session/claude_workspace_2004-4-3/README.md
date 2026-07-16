# claude_workspace_2004-4-3

4-3 [프로젝트 2] 산출물 — 4-2 Agent Execution Graph를 메인 세션이 리프마다 sub agent를 띄워
실제로 돌리는 작업분해 하네스 v1. Claude Code에서 이 폴더를 열면 바로 한 바퀴 돌릴 수 있게 입력
체인과 `.claude` 설정을 함께 깔아 두었다.

## 의존 체인

```text
inputs/ac-tree.md                  (4-1 · L1~L6 작업 트리, 분업 가치 판정)
        │
inputs/handoff-template.md         (4-2 · handoff 양식 v1 빈 골격)
        │
inputs/review-gate.md              (4-3 · format → evidence → FN gate)
        │
inputs/agent-execution-graph.md    (4-2 · 위 셋을 이은 그래프 한 장)
        │
task-decomposition-harness-v1.md ← 이 하네스 (그래프를 실행 protocol로 옮김)
```

## 파일

| 경로 | 무엇 |
|---|---|
| `task-decomposition-harness-v1.md` | 하네스 본체: 계약·메인 루프·runtime surface·dry-run·v2 backlog |
| `CLAUDE.md` | Claude Code 운영 지침 (이 폴더에서 작업할 때 자동으로 읽힌다) |
| `.claude/settings.json` | hook 등록 (SessionStart · PostToolUse) |
| `.claude/hooks/session_start.py` | 세션 시작 시 하네스 규약을 컨텍스트에 주입 |
| `.claude/hooks/review_gate.py` | `handoffs/` 산출물의 결정론 사전 검수 (format·인용 줄·금지값) |
| `.claude/agents/{ask,build,review}.md` | 리프별 sub agent 정의 |
| `.claude/skills/using-task-harness/SKILL.md` | 메인 루프·dispatch 계약 메타 스킬 |
| `inputs/` | 입력 체인 (위 의존 체인 + 근거 대조 원문 meeting.md + build handoff 결과) |
| `handoffs/` | sub agent가 돌려준 handoff 기록 (런타임) |
| `state/run-log.md` | 리프별 verdict + route 상태판 |

## 시작 (Claude Code)

1. 이 폴더(`claude_workspace_2004-4-3/`)를 프로젝트 루트로 열거나 `cd` 한다. SessionStart hook이
   하네스 규약을 띄운다.
2. 한 리프를 골라 Task 도구로 sub agent를 띄운다. 예 — review 리프:

   ```
   Task(subagent_type="review", prompt="""
   L6 도메인 제약 위반 검사를 맡아라.
   검수 대상: inputs/handoff-L3-result.md
   근거 원문: inputs/meeting.md
   gate 단계: inputs/review-gate.md
   verdict와 근거 줄, 다음 행동을 handoffs/handoff-L6-review.md 에 handoff로 써라.
   """)
   ```
3. sub agent가 `handoffs/`에 handoff를 쓰면 PostToolUse hook이 format·인용 줄·금지값을 자동 검사한다.
4. verdict로 route를 정하고 `state/run-log.md`에 한 줄 남긴다.

## dry-run 결과

이미 한 바퀴 돌려 둔 결과가 `task-decomposition-harness-v1.md` §4와 `handoffs/`,
`state/run-log.md`에 있다. 같은 입력(`inputs/handoff-L3-result.md`)을 다시 넣으면 같은 규칙에 걸려
같은 verdict가 나와야 한다 — 단, 의미 판정은 LLM-judge라 v1에서 100% 재현은 보장하지 않는다(5장 과제).
