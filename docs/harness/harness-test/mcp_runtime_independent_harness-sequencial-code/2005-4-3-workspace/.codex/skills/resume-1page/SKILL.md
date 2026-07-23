---
name: resume-1page
description: Use when the user types "$resume-1page" or asks Codex to resume the meeting-to-1page harness after ask-back. Applies answers, resumes the graph-level MCP workflow, and verifies exit output.
---

# Resume 1page

Resume the meeting-to-1page harness v2 from the workspace root:

`<workspace-절대경로>`

## Inputs

Parse user arguments in this order:

1. `answers`: default `inputs/answers.md`
2. `work_adapter`: default `replay`; allowed `replay`, `claude`, `codex`
3. `review_adapter`: default `local`; allowed `local`, `live`, `mcp`

## Preferred Path

Call MCP server `meeting-to-1page-harness`:

`resume_meeting_to_1page(answers, work_adapter, review_adapter)`

Report:

- `route`
- `final_1page` if route is `exit`
- `run_log_tail`

## Fallback

If the MCP tool is unavailable, run:

```bash
cd <workspace-절대경로>
python3 harness_v2_server.py resume --answers <answers> --work-adapter <work_adapter> --review-adapter <review_adapter>
```

## Verification

After exit, run both gates:

```bash
python3 .codex/hooks/review_gate.py handoffs/handoff-L5-review.md --leaf L5
python3 .codex/hooks/review_gate.py handoffs/handoff-L6-review.md --leaf L6
```

Both must print `route: pass`. If either fails, report the failing route and do not claim exit is verified.
