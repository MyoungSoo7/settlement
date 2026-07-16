#!/usr/bin/env python3
"""mcp_client_demo.py — review_server.py 를 MCP 서버로 띄우고 review_parallel 툴을 실제 호출한다.

세션(클라이언트)이 MCP 서버의 tool 을 호출하면, 그 tool 안에서 Claude Agent SDK ∥ codex exec 가
병렬로 돈다 — 5장 척추 "툴이 LLM을 쓴다" 를 MCP 프로토콜 레벨에서 재현한 reproducible 데모.

실행: python3 mcp_client_demo.py [build_handoff_path]
"""

from __future__ import annotations

import asyncio
import json
import os
import sys
from pathlib import Path

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

WS = Path(__file__).resolve().parent


async def main() -> int:
    build = Path(sys.argv[1]) if len(sys.argv) > 1 else WS / "handoffs" / "handoff-L3L4-build.md"
    draft = build.read_text(encoding="utf-8")
    source = (WS / "inputs" / "meeting.md").read_text(encoding="utf-8")

    params = StdioServerParameters(
        command=sys.executable,
        args=[str(WS / "review_server.py")],
        env=dict(os.environ),          # PATH 등 상속 (claude/codex 바이너리 탐색). 서버는 python.
        cwd=str(WS),
    )
    print(f"[client] MCP 서버 기동: python3 review_server.py  (draft={build.name})")
    async with stdio_client(params) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            tools = await session.list_tools()
            print(f"[client] tools/list → {[t.name for t in tools.tools]}")

            print("[client] tools/call → review_parallel(draft, source)  "
                  "(내부에서 Claude Agent SDK ∥ codex exec 병렬 실행, 수십 초 소요)")
            result = await session.call_tool("review_parallel", {"draft": draft, "source": source})

            payload = getattr(result, "structuredContent", None)
            if not payload and result.content:
                # 텍스트 블록에서 JSON 복원
                for block in result.content:
                    text = getattr(block, "text", "")
                    if text.strip().startswith("{"):
                        payload = json.loads(text)
                        break
            print("[client] 결과:")
            print(json.dumps(payload, ensure_ascii=False, indent=2))
            # 요약
            if payload:
                cit = payload.get("citation", {}).get("verdict", "?")
                fac = payload.get("facts", {}).get("verdict", "?")
                print(f"\n[client] L5 인용(Claude Agent SDK)={cit} ∥ "
                      f"L6 사실·범위(codex exec)={fac} → route={payload.get('route')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
