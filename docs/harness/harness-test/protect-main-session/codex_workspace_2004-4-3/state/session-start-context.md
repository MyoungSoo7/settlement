[작업분해 하네스 워크스페이스 (2004-4-3) — 세션 시작 안내]

본 workspace는 Agent Execution Graph를 실제 sub agent handoff 루프로 돌리는 실습 공간이다.

시작 시 먼저 확인할 파일:
  - README.md
  - Agent.md
  - harness-v1.md
  - state/harness-state.md
  - state/run-log.md
  - .codex/skills/using-task-harness/SKILL.md

핵심 규약:
  - 메인 세션이 상태를 소유한다. sub agent는 리프 하나만 맡는 disposable 실행 단위다.
  - 리프 node는 L1, L5, L6이다.
  - L3 6칸 맵핑과 L4 확인 필요 항목은 sub agent가 아니라 MAIN INLINE BUILD다.
  - 모든 리프는 같은 루프를 따른다:
      handoff brief -> sub agent handoff result -> review gate -> route
  - review gate 순서는 항상 format -> evidence -> false-negative correction이다.
  - route는 pass / rework / ask-back / exit 중 하나로만 남긴다.
  - 리프별 verdict와 다음 행동은 state/harness-state.md에 보존한다.

Node / Edge:
  START
    -> L1 결정 항목 추출 질문
    -> MAIN INLINE BUILD
    -> L5 보존 표현 인용 검사
    -> MERGE

  START
    -> L1 결정 항목 추출 질문
    -> MAIN INLINE BUILD
    -> L6 도메인 제약 위반 검사
    -> MERGE

Sub agent handoff:
  main -> sub agent:
    leaf name, purpose, input, scope, forbidden claims, done condition, evidence source

  sub agent -> main:
    ## From task
    ## Result
    - 결과 요약
    - 판정
    - 남은 질문
    - 다음 세션의 첫 행동

검수 hook:
  python3 .codex/hooks/review_gate.py handoffs/dry-run-L1-handoff.md --leaf L1

자동 hook:
  - SessionStart: 이 안내를 출력한다.
  - PostToolUse(Edit|Write|apply_patch): handoffs/*.md의 format, meeting.md 줄 실재, 60% 단정을 검수한다.

Codex custom agents:
  - .codex/agents/ask.toml
  - .codex/agents/build.toml
  - .codex/agents/review.toml

Codex hook note:
  이 안내는 .codex/hooks.json 의 SessionStart(startup|resume|clear|compact)에서 실행된다.
  프로젝트 로컬 hook은 Codex에서 이 .codex layer를 trusted로 둔 경우에 로드된다.
  필요하면 Codex CLI에서 /hooks 를 열어 hook을 review/trust 한다.

[workspace file check]
  - README.md: ok
  - Agent.md: ok
  - harness-v1.md: ok
  - state/harness-state.md: ok
  - state/run-log.md: ok
  - .codex/hooks.json: ok
  - .codex/hooks/review_gate.py: ok
  - .codex/agents/ask.toml: ok
  - .codex/agents/build.toml: ok
  - .codex/agents/review.toml: ok
  - .codex/skills/using-task-harness/SKILL.md: ok
