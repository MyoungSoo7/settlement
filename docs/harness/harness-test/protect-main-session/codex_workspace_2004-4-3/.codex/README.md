# Codex Local Setup

This `.codex` folder contains local harness support files for 2004-4-3.

## Hooks

- `config.toml`: keeps Codex hooks enabled for this project layer.
- `hooks.json`: registers `SessionStart` for `startup|resume|clear|compact`.
- `hooks.json`: registers `PostToolUse` for `Edit|Write|apply_patch`.
- `hooks/session_start.py`: prints the harness rules at session start.
- `hooks/review_gate.py`: deterministic markdown gate helper and PostToolUse handoff precheck.

Run from this workspace:

```bash
python3 .codex/hooks/session_start.py
python3 .codex/hooks/review_gate.py handoffs/dry-run-L1-handoff.md --leaf L1
```

`session_start.py` writes `state/session-start-context.md` and exits 0 without stdout. This avoids Codex rejecting SessionStart on strict stdout JSON validation.

The review hook is intentionally small and tool-independent. It validates the handoff artifact; it does not spawn agents.

Codex project-local hooks load when the project `.codex` layer is trusted. If Codex reports that hooks need review, open `/hooks` and trust this workspace hook.

## Agents

- `agents/ask.toml`: L1 decision-question sub agent.
- `agents/build.toml`: optional L3/L4 build sub agent.
- `agents/review.toml`: L5/L6 review-gate sub agent.

## Skill

- `skills/using-task-harness/SKILL.md`: meta skill for the handoff -> review -> route loop.
