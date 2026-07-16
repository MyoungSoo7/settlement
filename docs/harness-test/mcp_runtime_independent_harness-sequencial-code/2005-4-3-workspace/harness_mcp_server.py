#!/usr/bin/env python3
"""harness_mcp_server.py — 하네스 graph 전체를 MCP 툴 하나로 노출.

review_server.py 는 graph 중 `review_parallel` 한 node 만 MCP 로 노출한다.
이 서버는 그 위 한 단계 — capability graph 전체(requirements_contract → ... → route)를
MCP 툴 하나로 감싼다. 메인 세션(Claude Code/Codex)이 이 툴을 한 번 콜하면,
그 안에서 harness_v2_server 의 결정론 orchestrator 가 13개 node 를 잠긴 순서로 돌리고
route 와 산출물 경로를 돌려준다. "툴이 LLM 을 쓴다" 를 graph 레벨에서 실현.

노출 툴:
  - run_meeting_to_1page(work_adapter, review_adapter)  : fresh run 한 바퀴 → route
  - resume_meeting_to_1page(answers, work_adapter, review_adapter) : ask-back 회신 반영 → exit
  - run_full_meeting_to_1page(answers, work_adapter, review_adapter) : run → ask-back → resume → exit

기본값은 replay/local (외부 호출 없이 결정론·빠름). work_adapter="claude"|"codex" 면 실제
sub agent 디스패치, review_adapter="mcp"|"live" 면 실제 Claude∥Codex 검수까지 graph 안에서 돈다.
"""

from __future__ import annotations

from typing import Any

import anyio
from pathlib import Path

from mcp.server.fastmcp import FastMCP

import harness_v2_server as H

mcp = FastMCP("meeting-to-1page-harness")


def _runlog_tail(n: int = 6) -> list[str]:
    if not H.RUNLOG_JSONL.exists():
        return []
    import json
    rows = [json.loads(l) for l in H.read(H.RUNLOG_JSONL).splitlines() if l.strip()]
    return [f"{r['node']}: {r['status']}" + (f" → {r['route']}" if r.get("route") else "")
            for r in rows[-n:]]


def _result(route: str) -> dict[str, Any]:
    out: dict[str, Any] = {"route": route, "run_log_tail": _runlog_tail()}
    if route == "exit" and H.FINAL_1PAGE.exists():
        out["final_1page"] = str(H.FINAL_1PAGE.relative_to(H.WS))
    if route == "ask-back" and H.PENDING.exists():
        out["pending_external"] = str(H.PENDING.relative_to(H.WS))
    return out


def _resume_from_answers(answers: str, work_adapter: str, review_adapter: str,
                         run_id: str = "mcp-run-2-resume") -> str:
    state = H.load_run_state()
    if state is None:
        return "no-run-state"
    h = H.HarnessV2(run_id, "codex", review_adapter, work_adapter)
    ok = H.apply_answers(state, Path(answers), h.log, run_id)
    if not ok:
        return "blocked exit"
    route = h.traverse(state, start=state.get("resume_target") or "fill_handoff")
    if H.PENDING.exists() and route == "exit":
        H.write(H.STATE / "pending-external.resolved.md",
                f"> RESOLVED via MCP {run_id} — route=exit\n\n" + H.read(H.PENDING))
        H.PENDING.unlink()
    H.save_run_state(state)
    return route


@mcp.tool()
async def run_meeting_to_1page(work_adapter: str = "replay",
                               review_adapter: str = "local") -> dict[str, Any]:
    """회의록→1page 하네스 capability graph 를 fresh 로 한 번 실행한다.

    requirements_contract → ... → review_gate → review_parallel → merge_verdict → route.
    work_adapter: replay(4장 녹화 회수) | claude(Claude Agent SDK) | codex(Codex CLI worker).
    review_adapter: local(결정론) | live(in-process Claude∥Codex) | mcp(review_server 호출).
    반환: {route, final_1page|pending_external, run_log_tail}.
    """
    def _work() -> str:
        H.clear_produced()
        state = H.fresh_state("mcp-run-1")
        h = H.HarnessV2("mcp-run-1", "codex", review_adapter, work_adapter)
        route = h.traverse(state)
        H.save_run_state(state)
        return route
    route = await anyio.to_thread.run_sync(_work)   # 동기 graph 를 스레드에서 (내부 asyncio.run 안전)
    return _result(route)


@mcp.tool()
async def resume_meeting_to_1page(answers: str = "inputs/answers.md",
                                  work_adapter: str = "replay",
                                  review_adapter: str = "local") -> dict[str, Any]:
    """ask-back 으로 멈춘 run 을 answers(2장 채택)로 갱신하고 resume_target 부터 이어 돌린다."""
    def _work() -> str:
        return _resume_from_answers(answers, work_adapter, review_adapter)
    route = await anyio.to_thread.run_sync(_work)
    return _result(route)


@mcp.tool()
async def run_full_meeting_to_1page(answers: str = "inputs/answers.md",
                                    work_adapter: str = "replay",
                                    review_adapter: str = "local") -> dict[str, Any]:
    """fresh run 부터 ask-back 회신 resume 까지 graph-level MCP 한 콜로 전체 실행한다.

    정상 경로는 run route=ask-back 을 만든 뒤, answers 로 requirements_update 를 적용하고
    resume_target 부터 재디스패치해 최종 route=exit 을 반환한다.
    """
    def _work() -> str:
        H.clear_produced()
        state = H.fresh_state("mcp-full-run-1")
        h = H.HarnessV2("mcp-full-run-1", "codex", "local", work_adapter)
        first_route = h.traverse(state)
        H.save_run_state(state)
        if first_route != "ask-back":
            return first_route
        return _resume_from_answers(answers, work_adapter, review_adapter,
                                    run_id="mcp-full-run-2-resume")
    route = await anyio.to_thread.run_sync(_work)
    return _result(route)


if __name__ == "__main__":
    mcp.run()
