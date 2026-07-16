#!/usr/bin/env python3
"""review_server.py — 회의록 1page 검수 MCP (Claude ∥ Codex). review_parallel node 구현 예시.

이 파일은 하네스 v2 graph 중 `review_parallel` node 하나를 MCP로 코드화한 예시다.
바깥에서 보면 `review_parallel(draft, source)` 콜 하나, 안에서는 Claude·Codex 두 세션이
병렬로 돈다 — 5-2 런타임 독립이 코드로 실현되는 지점.

  맡는 것  : 병렬 검수 실행, 결과 merge, partial pass 보존, retry route 반환.
  맡지 않는 것: 문제 정의, 1page 작성 규칙 전체, 최종 채택 판단, ask-back 질문의 답 추정.

런타임 (둘 다 SDK/CLI 호출, raw API 아님 — 기존 인증 사용, 별도 키 불필요):
  - 인용 출처 검사(L5) → Claude  : Claude Agent SDK `query()` (Ouroboros providers/claude_code_adapter.py 와 같은 런타임)
  - 사실·범위 검사(L6) → Codex   : `codex exec` 비대화형 CLI       (Ouroboros providers/codex_cli_adapter.py 와 같은 런타임)
  같은 `(prompt) -> text` 인터페이스, 런타임만 다르다. 분기점은 어댑터 하나(Ouroboros providers/factory.py 구조).

harness_v2_server.py 는 기본값으로 local 결정론 judge 를 쓰고(외부 호출 없이 재현),
`--review-adapter live` 일 때만 아래 어댑터를 lazy import 해서 실제 두 런타임으로 검수한다.
"""

from __future__ import annotations

import asyncio
import json
import os
import tempfile
from pathlib import Path
from typing import Any

# 현재 Claude Code 세션 안에서 SDK 가 claude CLI 를 다시 띄울 때 중첩 실행 차단을 우회한다.
# (CLAUDECODE 가 set 이면 "cannot be launched inside another Claude Code session" 으로 종료됨)
_SDK_ENV = {"CLAUDECODE": ""}
_REVIEW_MODEL = os.environ.get("REVIEW_MODEL", "claude-haiku-4-5-20251001")


# 어댑터 둘: 같은 (prompt) -> text 모양, 런타임만 다르다 (5-2 런타임 독립) -----------------


async def run_on_claude(prompt: str) -> str:
    """인용 출처 검사 런타임 = Claude. Claude Agent SDK query() 로 텍스트 블록을 이어붙인다."""
    from claude_agent_sdk import (                       # lazy import (pip install claude-agent-sdk)
        query, ClaudeAgentOptions, AssistantMessage, TextBlock,
    )

    options = ClaudeAgentOptions(
        system_prompt="너는 회의록 1page 인용 검사기다. JSON으로만 답한다.",
        allowed_tools=[],          # 도구 없이 판정만 (1턴)
        max_turns=1,
        model=_REVIEW_MODEL,
        env=_SDK_ENV,
    )
    parts: list[str] = []
    async for msg in query(prompt=prompt, options=options):
        if isinstance(msg, AssistantMessage):
            parts.extend(b.text for b in msg.content if isinstance(b, TextBlock))
    return "".join(parts)


async def run_on_codex(prompt: str) -> str:
    """사실·범위 검사 런타임 = Codex. 프롬프트는 stdin, 최종 답은 --output-last-message 파일로."""
    fd, out_path = tempfile.mkstemp(suffix=".txt")
    os.close(fd)
    try:
        proc = await asyncio.create_subprocess_exec(
            "codex", "exec", "--json", "--skip-git-repo-check",
            "--output-last-message", out_path,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        _, stderr = await proc.communicate(prompt.encode("utf-8"))     # 프롬프트 → stdin
        if proc.returncode != 0:
            err = stderr.decode("utf-8", errors="replace").strip()
            return json.dumps({
                "verdict": "error",
                "violations": [f"codex exec failed rc={proc.returncode}: {err[:300]}"],
            }, ensure_ascii=False)
        return Path(out_path).read_text(encoding="utf-8").strip()      # 최종 메시지 ← 파일
    finally:
        os.unlink(out_path)


def as_verdict(text: str) -> dict:
    start, end = text.find("{"), text.rfind("}")                       # 펜스/설명을 걷어내고 JSON만
    try:
        return json.loads(text[start:end + 1])                         # {"verdict": "pass"/"fail", "violations": [...]}
    except (ValueError, json.JSONDecodeError):
        return {"verdict": "error", "raw": text[:300]}


def _number_lines(source: str) -> str:
    """원문에 줄번호를 붙인다 — 인용 검사기가 meeting.md:NN 을 줄 단위로 대조하게."""
    return "\n".join(f"{i}: {line}" for i, line in enumerate(source.splitlines(), 1))


async def run_review(runner, instruction: str, draft: str, source: str) -> dict:
    n = len(source.splitlines())
    prompt = (
        instruction.format(n=n)
        + "\n\n[1page 초안(handoff)]\n" + draft
        + "\n\n[회의록 원문 (줄번호 포함, 총 " + str(n) + "줄)]\n" + _number_lines(source) + "\n\n"
        + '결과는 {"verdict": "pass" 또는 "fail", "violations": ["..."]} JSON으로만 답해줘. '
        + 'violations 는 문자열 배열로만. 위반이 없으면 violations 는 빈 배열.'
    )
    return as_verdict(await runner(prompt))


async def check_citation(draft: str, source: str) -> dict:             # 인용 출처 검사 → Claude
    return await run_review(
        run_on_claude,
        "회의록을 1page로 옮긴 초안의 인용 줄번호(meeting.md:NN)만 아래 규칙 그대로 검사해라.\n"
        "1) NN 이 1~{n} 범위 안이면 그 줄은 실재한다.\n"
        "2) 한 칸(셀)에 근거 줄이 여러 개면, 그 중 하나라도 셀 주제와 관련되면 그 칸은 통과다. "
        "약하게 관련되거나 부수적인 줄(예: 섹션 제목 줄)이 섞여 있어도 감점하지 않는다.\n"
        "3) 보존 표현 verbatim 여부·작성 품질·완성도·서술 방식은 보지 않는다(별도 gate).\n"
        "violations 에는 (a) 1~{n} 밖의 줄번호, (b) 그 칸의 어떤 근거 줄도 주제와 전혀 무관한 경우만 적는다.\n"
        "그런 위반이 하나도 없으면 반드시 verdict=pass 로 답해라.",
        draft, source)


async def check_facts(draft: str, source: str) -> dict:                # 사실·범위 검사 → Codex
    return await run_review(
        run_on_codex,
        "초안이 회의록 밖 외부 사실을 단정했거나, 범위 밖 항목(전체 리뉴얼·결제 모듈 모달·환불 기준 명문화)을 "
        "해결 방향에 넣었거나, 미확인 수치(CS 문의 60%)를 본문에 출처 없이 단정했는지만 검사해줘. "
        "확인 필요 항목에 불확실성 표지와 함께 보존된 60%는 위반이 아니다. "
        "성공 기준 칸의 15%/4주 같은 측정 지표가 `사용자 회신`, `requirements_update`, "
        "`acceptance.metric` 등 사람 회신/갱신 근거와 함께 적혀 있으면 회의록 밖 임의 단정이 아니라 "
        "ask-back으로 확정된 요구사항이므로 위반이 아니다. 위반만 violations 로 적어줘.",
        draft, source)


async def review_parallel_async(draft: str, source: str) -> dict:
    """Claude·Codex 두 런타임 세션을 asyncio.gather 로 동시에 실행하고 merge."""
    citation, facts = await asyncio.gather(
        check_citation(draft, source),
        check_facts(draft, source),
    )
    failed = [r for r in (citation, facts) if r.get("verdict") != "pass"]
    return {
        "citation": citation,                          # L5 (Claude Agent SDK)
        "facts": facts,                                # L6 (codex exec)
        "passed": [r for r in (citation, facts) if r.get("verdict") == "pass"],
        "failed": failed,
        "route": "exit" if not failed else "retry",    # 한쪽만 실패면 통과 결과 보존
    }


def review_parallel_live(draft: str, source: str) -> dict:
    """harness_v2_server.py 가 --review-adapter live 일 때 호출하는 동기 wrapper."""
    return asyncio.run(review_parallel_async(draft, source))


# MCP 서버 진입점 (FastMCP) — `python3 review_server.py` 로 stdio MCP 서버 기동 ---------------
def _serve() -> None:
    from mcp.server.fastmcp import FastMCP          # pip install mcp (lazy import)

    mcp = FastMCP("meeting-1pager-review")

    @mcp.tool()
    async def review_parallel(draft: str, source: str) -> dict[str, Any]:   # noqa: D401
        """회의록 1page 초안을 Claude(인용) ∥ Codex(사실·범위)로 병렬 검수하고 merge."""
        return await review_parallel_async(draft, source)

    mcp.run()


if __name__ == "__main__":
    _serve()
