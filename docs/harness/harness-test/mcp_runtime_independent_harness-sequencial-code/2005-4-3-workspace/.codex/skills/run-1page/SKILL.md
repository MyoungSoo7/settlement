---
name: run-1page
description: Use when the user types "$run-1page" or asks Codex to run the meeting-to-1page harness v2. Executes the graph-level MCP run tool when available, with Python CLI fallback, and reports route, pending ask-back, or final 1page output.
---

# Run 1page

Run the meeting-to-1page harness v2 from the workspace root:

`<workspace-절대경로>`

## Inputs

Parse user arguments in this order:

1. `work_adapter`: default `replay`; allowed `replay`, `claude`, `codex`
2. `review_adapter`: default `local`; allowed `local`, `live`, `mcp`

If the user asks for a full one-call run through resume, use `run_full_meeting_to_1page` with `answers="inputs/answers.md"`.

## Preferred Path

Call MCP server `meeting-to-1page-harness`:

- Normal run: `run_meeting_to_1page(work_adapter, review_adapter)`
- Full run: `run_full_meeting_to_1page(answers="inputs/answers.md", work_adapter, review_adapter)`

Report:

- `route`
- `pending_external` if route is `ask-back`
- `final_1page` if route is `exit`
- `run_log_tail`

## Fallback

If the MCP tool is unavailable, run:

```bash
cd <workspace-절대경로>
python3 harness_v2_server.py run --work-adapter <work_adapter> --review-adapter <review_adapter>
```

## Reporting

- If route is `ask-back`, summarize `state/pending-external.md` and tell the user to run `$resume-1page`.
- If route is `exit`, summarize `handoffs/final-1page-draft.md`.
- Do not invent metric answers; only `inputs/answers.md` may close the ask-back.
