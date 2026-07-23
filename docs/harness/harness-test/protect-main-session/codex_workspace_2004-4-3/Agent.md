# Agent Instructions — 2004-4-3 Codex Workspace

## Scope

This workspace turns `practice-materials/chapter-04/2004-4-2-task-breakdown-blueprint.md` into a runnable task-decomposition harness v1.

## Working Language

- Use Korean for harness documents and handoff results.
- Use Python 3 for hooks, validators, and small automation.
- Keep generated handoffs short enough for a main session to review in one screen.

## Main Session Rule

The main session owns state. Sub agents are disposable execution units.

For every leaf, the main session must:

1. send a brief using the handoff contract,
2. receive one handoff markdown back,
3. run the review gate in this order: format -> evidence -> false-negative correction,
4. route to `pass`, `rework`, `ask-back`, or `exit`,
5. append the verdict and next action to `state/harness-state.md`.

## Sub Agent Rule

Sub agents only return the requested handoff. They do not own cross-leaf state and should not rewrite files unless the main session explicitly assigns a file path.

Use the project custom agents when a sub agent is requested:

- `.codex/agents/ask.toml`
- `.codex/agents/build.toml`
- `.codex/agents/review.toml`

If the current Codex surface exposes named agent spawning, use `ask`, `build`, or `review` as the agent name. If not, use the same instructions as a brief in the available subagent tool.

## Leaf Nodes

- `L1 결정 항목 추출 질문` — ask
- `L5 보존 표현 인용 검사` — review
- `L6 도메인 제약 위반 검사` — review

`L3 6칸 맵핑` and `L4 확인 필요 항목 한 칸 모음` stay in the main session as inline build work.

## Review Gate Rule

- `format`: required sections and verdict fields exist.
- `evidence`: claims point back to `meeting.md` lines or quoted meeting evidence.
- `false-negative correction`: prose differences pass; unsupported values, scope expansion, and unresolved decision claims do not pass.

PostToolUse hook scope:

- `.codex/hooks/review_gate.py` runs automatically for handoff markdown writes when Codex loads `.codex/hooks.json`.
- It only enforces the deterministic slice: handoff structure, `meeting.md:NN` line existence, and unqualified `60%`.
- Semantic review still belongs to the review sub agent.

## Exit Rule

Exit instead of looping when the same rework reason repeats twice, or when an external decision is required before a verdict can change.
