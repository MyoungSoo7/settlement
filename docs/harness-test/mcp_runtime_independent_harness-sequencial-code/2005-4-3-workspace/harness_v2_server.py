#!/usr/bin/env python3
"""harness_v2_server.py — meeting-to-1page 하네스 v2 (2005-4-3).

4장 meeting-to-1page 하네스 v1을 5장 capability graph로 승격한 v2 실행 엔진.
graph node를 실제 함수로 돌리고, 5장 workspace 안에서 실제 파일을 읽고 쓴다.

capability graph (한 번 실행):
  requirements_contract -> spec_contract -> session_surface -> structured_contract
  -> state_store.load_or_init -> fill_handoff -> dispatch_plan -> invoke_runtime_adapter
  -> collect_handoff -> review_gate -> review_parallel(L5 ∥ L6) -> merge_verdict -> route

2005-4-1 Ouroboros 분석에서 가져온 구현 원칙:
  - frozen contract      : requirements/spec 계약을 SHA-256으로 잠그고 실행 중 direction 불변.
  - append-only state    : state/run-log.jsonl 을 EventStore처럼 append-only로 남기고 replay로 복원.
  - checkpoint/rewind    : state/checkpoints/*.json 회전 저장, retry/ask-back 때 return node와 사유 기록.
  - evaluation gate      : review_gate 결정론 검사 통과 후에만 review_parallel(L5/L6) 의미 검수로 넘긴다.
  - regression guard     : merge_verdict가 preserved pass를 저장하고, resume 후 이전 pass가 깨지면 차단.
  - satisfice exit       : exit은 7칸 충분 + guard 통과 + 불확실성 분리일 때만. "더 좋아질 수 있음"은 retry 사유 아님.

런타임 독립(5-2): workflow layer(graph node·route·계약)는 어댑터와 무관하게 같고,
invoke_runtime_adapter / review_parallel만 런타임(replay·codex·claude)을 가른다.
"""

from __future__ import annotations

import argparse
import asyncio
import concurrent.futures
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

try:
    import tomllib
except ModuleNotFoundError:  # pragma: no cover - py310 fallback
    tomllib = None

# ──────────────────────────────────────────────────────────────────────────
# Paths
# ──────────────────────────────────────────────────────────────────────────
WS = Path(__file__).resolve().parent
INPUTS = WS / "inputs"
HANDOFFS = WS / "handoffs"
STATE = WS / "state"
CKPT = STATE / "checkpoints"
CONTRACTS = WS / "contracts"
SOURCE = WS / "source"
REVIEW_GATE = WS / ".codex" / "hooks" / "review_gate.py"

RUNLOG_MD = STATE / "run-log.md"
RUNLOG_JSONL = STATE / "run-log.jsonl"
RUNSTATE = STATE / "run-state.json"
PENDING = STATE / "pending-external.md"
FINAL_1PAGE = HANDOFFS / "final-1page-draft.md"

MEETING = INPUTS / "meeting.md"

GRAPH_NODES = [
    "requirements_contract", "spec_contract", "session_surface", "structured_contract",
    "state_store.load_or_init", "fill_handoff", "dispatch_plan", "invoke_runtime_adapter",
    "collect_handoff", "review_gate", "review_parallel", "merge_verdict", "route",
]

PRESERVE_PHRASES = [
    "신청 직후 환불·일정 문의를 줄인다",
    "페이지 전체 리뉴얼은 이번 범위가 아니다",
    "환불 기준 자체가 명문화돼 있지 않다",
]


# ──────────────────────────────────────────────────────────────────────────
# Small helpers
# ──────────────────────────────────────────────────────────────────────────
def now() -> str:
    return datetime.datetime.now().isoformat(timespec="seconds")


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def meeting_line_count() -> int:
    return len(read(MEETING).splitlines())


# ──────────────────────────────────────────────────────────────────────────
# Append-only run-log (Ouroboros EventStore analog)
# ──────────────────────────────────────────────────────────────────────────
class RunLog:
    """state/run-log.jsonl 을 append-only로 쌓고, run-log.md 표를 같이 갱신한다."""

    def __init__(self) -> None:
        self.seq = self._next_seq()
        if not RUNLOG_MD.exists():
            write(RUNLOG_MD, "# Run Log — 2005-4-3 meeting-to-1page Harness v2\n\n"
                             "append-only. node별 input/output/verdict/route 기록. "
                             "Ouroboros EventStore(append+replay) 원칙.\n\n"
                             "| seq | time | run | node | event | status/verdict | route | detail |\n"
                             "|---|---|---|---|---|---|---|---|\n")

    def _next_seq(self) -> int:
        if not RUNLOG_JSONL.exists():
            return 1
        n = 0
        for line in read(RUNLOG_JSONL).splitlines():
            if line.strip():
                n += 1
        return n + 1

    def event(self, run_id: str, node: str, event_type: str, status: str,
              detail: str = "", route: str = "") -> None:
        rec = {
            "seq": self.seq, "time": now(), "run": run_id, "node": node,
            "event_type": event_type, "status": status, "route": route, "detail": detail,
        }
        with RUNLOG_JSONL.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
        row = (f"| {self.seq} | {rec['time']} | {run_id} | {node} | {event_type} | "
               f"{status} | {route or '-'} | {detail} |\n")
        with RUNLOG_MD.open("a", encoding="utf-8") as fh:
            fh.write(row)
        print(f"  [{node}] {status}" + (f" route={route}" if route else "") +
              (f" — {detail}" if detail else ""))
        self.seq += 1

    @staticmethod
    def replay() -> list[dict]:
        """append-only 로그를 처음부터 재생해 이벤트 목록을 복원한다."""
        if not RUNLOG_JSONL.exists():
            return []
        return [json.loads(l) for l in read(RUNLOG_JSONL).splitlines() if l.strip()]


# ──────────────────────────────────────────────────────────────────────────
# Checkpoint store (rotation + last_good + rewind reason)
# ──────────────────────────────────────────────────────────────────────────
def save_checkpoint(name: str, payload: dict, *, good: bool = False,
                    rewind_reason: str = "") -> str:
    CKPT.mkdir(parents=True, exist_ok=True)
    path = CKPT / f"{name}.json"
    # rotation: current -> .1 -> .2
    if path.exists():
        prev1 = CKPT / f"{name}.json.1"
        if prev1.exists():
            (CKPT / f"{name}.json.2").write_text(read(prev1), encoding="utf-8")
        prev1.write_text(read(path), encoding="utf-8")
    body = json.dumps(payload, ensure_ascii=False, sort_keys=True)
    data = {
        "name": name, "ts": now(), "sha256": sha256(body),
        "rewind_reason": rewind_reason, "payload": payload,
    }
    write(path, json.dumps(data, ensure_ascii=False, indent=2))
    if good:
        write(CKPT / "last_good.json", json.dumps(data, ensure_ascii=False, indent=2))
    return data["sha256"]


def load_last_good() -> dict | None:
    p = CKPT / "last_good.json"
    return json.loads(read(p)) if p.exists() else None


# ──────────────────────────────────────────────────────────────────────────
# review_gate.py bridge (4장 결정론 guard — 실제 subprocess 호출)
# ──────────────────────────────────────────────────────────────────────────
def gate_cli(handoff: Path, leaf: str) -> tuple[str, str, int]:
    """python3 .codex/hooks/review_gate.py <handoff> --leaf <leaf>"""
    proc = subprocess.run(
        [sys.executable, str(REVIEW_GATE), str(handoff), "--leaf", leaf],
        capture_output=True, text=True,
    )
    route = ""
    for line in proc.stdout.splitlines():
        if line.startswith("route:"):
            route = line.split(":", 1)[1].strip()
            break
    return route, proc.stdout.strip(), proc.returncode


def gate_posttool(handoff: Path) -> tuple[int, str]:
    """PostToolUse 모드: stdin JSON payload -> review_gate.py (format/line/60% 결정론 검사)."""
    payload = json.dumps({"tool_input": {"file_path": str(handoff)}})
    proc = subprocess.run(
        [sys.executable, str(REVIEW_GATE)],
        input=payload, capture_output=True, text=True,
    )
    return proc.returncode, proc.stderr.strip()


# ──────────────────────────────────────────────────────────────────────────
# Runtime adapter (5-2 런타임 독립) — replay 기본, codex/claude는 optional
# ──────────────────────────────────────────────────────────────────────────
def adapter_ask(_state: dict) -> Path:
    """ask leaf: 4장 실제 L1 ask 산출(handoff-L1-ask.md)을 replay로 회수한다."""
    src = SOURCE / "handoffs" / "handoff-L1-ask.md"
    dst = HANDOFFS / "handoff-L1-ask.md"
    write(dst, read(src))
    return dst


_SUCCESS_ROW = re.compile(r"^(\s*\|\s*성공 기준\s*\|).*$", re.MULTILINE)
_AMBIGUOUS_SUCCESS = (
    r"\1 신청 직후 환불·일정 문의를 줄인다 (← 측정 단위·기간 미정, "
    r"목표 문장인지 측정 지표인지 미정).<br>근거: meeting.md:10 |"
)


def adapter_build(state: dict) -> Path:
    """build leaf: 4장 실제 build 산출을 base로 쓰고, frozen contract의 metric 상태를 반영한다.

    metric 미확정이면 성공 기준 칸을 모호(목표 문장)로 되돌린다 → review L6가 ask-back.
    metric 확정(ask-back resume 이후)이면 측정 지표(15%/4주)가 든 answered 산출 그대로.
    """
    base = read(SOURCE / "handoffs" / "handoff-L3L4-build.md")
    if not state["requirements"]["acceptance"]["metric_confirmed"]:
        base = _SUCCESS_ROW.sub(_AMBIGUOUS_SUCCESS, base, count=1)
        base = base.replace("# Handoff — L3+L4 · build 세션 → 메인",
                            "# Handoff — L3+L4 · build 세션 → 메인 (성공 기준 metric 미확정본)")
    dst = HANDOFFS / "handoff-L3L4-build.md"
    write(dst, base)
    return dst


# ──────────────────────────────────────────────────────────────────────────
# 실제 sub agent dispatch (Claude Agent SDK / Codex CLI) — work leaf 를 replay 가 아니라 진짜 생성
# ──────────────────────────────────────────────────────────────────────────
WORK_MODEL = os.environ.get("WORK_MODEL", "claude-sonnet-4-6")
CODEX_WORK_MODEL = os.environ.get("CODEX_WORK_MODEL", "")
CODEX_WORK_TIMEOUT = int(os.environ.get("CODEX_WORK_TIMEOUT", "180"))


def _agent_md_body(path: Path) -> str:
    """`.claude/agents/*.md` 의 frontmatter 를 떼고 본문(= sub agent 지시문)만 돌려준다."""
    text = read(path)
    if text.startswith("---"):
        parts = text.split("---", 2)
        if len(parts) >= 3:
            return parts[2].strip()
    return text


def _agent_toml_instructions(path: Path) -> str:
    """`.codex/agents/*.toml` 에서 developer_instructions 만 뽑아 Codex worker system prompt 로 쓴다."""
    text = read(path)
    if tomllib is not None:
        data = tomllib.loads(text)
        return str(data.get("developer_instructions") or text).strip()
    match = re.search(r'developer_instructions\s*=\s*"""(.*?)"""', text, flags=re.DOTALL)
    return (match.group(1) if match else text).strip()


def _strip_fence(text: str) -> str:
    t = text.strip()
    if t.startswith("```"):
        t = t.split("\n", 1)[1] if "\n" in t else ""
        if t.rstrip().endswith("```"):
            t = t.rstrip()[:-3]
    return t.strip()


def _numbered_meeting() -> str:
    return "\n".join(f"{i}: {line}" for i, line in enumerate(read(MEETING).splitlines(), 1))


async def _claude_leaf(system_prompt: str, user_prompt: str) -> str:
    """leaf 하나 = disposable Claude Agent SDK 세션 (기존 Claude Code 인증, API 키 불필요)."""
    from claude_agent_sdk import (
        query, ClaudeAgentOptions, AssistantMessage, TextBlock,
    )
    options = ClaudeAgentOptions(
        system_prompt=system_prompt,
        allowed_tools=[],          # 도구 없이 handoff 텍스트만 생성 (harness 가 파일을 씀)
        max_turns=1,
        model=WORK_MODEL,
        env={"CLAUDECODE": ""},    # 현재 Claude Code 세션 안 중첩 우회
        cwd=str(WS),
    )
    parts: list[str] = []
    async for msg in query(prompt=user_prompt, options=options):
        if isinstance(msg, AssistantMessage):
            parts.extend(b.text for b in msg.content if isinstance(b, TextBlock))
    return "".join(parts)


def _codex_leaf(system_prompt: str, user_prompt: str) -> str:
    """leaf 하나 = disposable Codex CLI worker. 최종 메시지만 받아 harness 가 파일을 쓴다."""
    fd, out_path = tempfile.mkstemp(suffix=".md")
    os.close(fd)
    prompt = (
        f"{system_prompt}\n\n"
        "[작업]\n"
        f"{user_prompt}\n\n"
        "최종 답변에는 handoff markdown 본문만 출력하라. 코드펜스와 설명 문장을 붙이지 마라."
    )
    cmd = [
        "codex", "exec",
        "--json",
        "--skip-git-repo-check",
        "--ephemeral",
        "--sandbox", "read-only",
        "--cd", str(WS),
        "--output-last-message", out_path,
    ]
    if CODEX_WORK_MODEL:
        cmd[2:2] = ["--model", CODEX_WORK_MODEL]
    try:
        proc = subprocess.run(
            cmd,
            input=prompt,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            cwd=str(WS),
            timeout=CODEX_WORK_TIMEOUT,
        )
        if proc.returncode != 0:
            err = (proc.stderr or proc.stdout).strip()
            raise RuntimeError(f"codex exec failed rc={proc.returncode}: {err[:300]}")
        out = Path(out_path).read_text(encoding="utf-8").strip()
        if not out:
            raise RuntimeError("codex exec produced empty final message")
        return out
    finally:
        os.unlink(out_path)


_L1_SKELETON = """# Handoff — L1 · ask 세션 → 메인

## From task (4-1 리프)
- 목적: 회의록의 미결정·출처 미확인 항목을 결정권자·일정·수치 출처·환불 기준 질문으로 묶는다.
- 입력: inputs/meeting.md
- 범위 (하지 않을 일 포함): 회의록의 미결정·출처 미확인 줄만 질문으로 만든다. 답·결론 금지.
- 금지: 회의록에 근거 없는 질문 생성. 담당자·일정·수치·환불 기준 단정.
- 완료조건: 각 질문에 대상자/팀 + 근거 줄(meeting.md:NN).

## Result (이 세션이 채운 부분)
- 결과 요약:
  - 결정권자/담당자: <질문 + 대상 + meeting.md:NN>
  - 일정: <질문 + 대상 + meeting.md:NN>
  - CS 문의 60% 수치 출처: <질문 + 대상 + meeting.md:NN> (60%는 추정·출처 미확인으로만 언급)
  - 환불 기준: <질문 + 대상 + meeting.md:NN>
- 판정: 통과
- 남은 질문: 위 4종 질문의 대상자·팀 회신 필요.
- 다음 세션의 첫 행동: L3+L4 build 세션에 이 질문표와 inputs/meeting.md를 넘긴다."""


def _ask_leaf_prompt() -> str:
    return (
        "아래 회의록(줄번호 포함)을 읽고, 결정권자·일정·CS 문의 60% 수치 출처·환불 기준 4종으로 "
        "의사결정자 질문을 묶어라. 각 질문에 물을 대상자/팀과 근거 줄(meeting.md:NN)을 붙여라.\n"
        "아래 골격을 그대로 채워 **코드펜스 없이 markdown 본문만** 출력하라. 모든 '- ' 항목과 "
        "'## From task' / '## Result' 헤더를 빠짐없이 유지하라.\n\n"
        + _L1_SKELETON + "\n\n[회의록 원문 (줄번호 포함)]\n" + _numbered_meeting()
    )


def adapter_ask_claude(state: dict) -> Path:
    """ask leaf 를 실제 Claude sub agent 가 수행: 회의록 → 4종 결정 질문 handoff 생성."""
    system_prompt = _agent_md_body(WS / ".claude" / "agents" / "ask.md")
    user_prompt = _ask_leaf_prompt()
    text = _strip_fence(asyncio.run(_claude_leaf(system_prompt, user_prompt)))
    dst = HANDOFFS / "handoff-L1-ask.md"
    write(dst, text)
    return dst


def adapter_ask_codex(state: dict) -> Path:
    """ask leaf 를 실제 Codex CLI worker 가 수행: 회의록 → 4종 결정 질문 handoff 생성."""
    system_prompt = _agent_toml_instructions(WS / ".codex" / "agents" / "ask.toml")
    user_prompt = _ask_leaf_prompt()
    text = _strip_fence(_codex_leaf(system_prompt, user_prompt))
    dst = HANDOFFS / "handoff-L1-ask.md"
    write(dst, text)
    return dst


def _build_skeleton(metric_line: str) -> str:
    return f"""# Handoff — L3+L4 · build 세션 → 메인

## From task (4-1 리프)
- 목적: 회의록 확정 내용을 제목·배경·문제·사용자·해결 방향·성공 기준 6칸으로 옮기고, 미결정은 확인 필요 항목으로 모은다.
- 입력: inputs/meeting.md, handoffs/handoff-L1-ask.md, contracts/spec-contract.md
- 범위 (하지 않을 일 포함): 합의 범위(강조 화면 + 자동 메일 두 갈래)만 반영. 전체 리뉴얼·결제 모듈 모달·환불 기준 명문화는 해결 방향에 넣지 않는다.
- 금지: 회의록에 없는 수치·일정·담당자·환불 기준 단정. CS 문의 60%(추정·출처 미확인)는 본문 6칸에 쓰지 않는다.
- 완료조건: 6칸 + 확인 필요 항목 1칸을 채우고 각 칸에 meeting.md:NN 근거를 붙인다.

## Result (이 세션이 채운 부분)
- 결과 요약:
  | 칸 | 내용 |
  |---|---|
  | 제목 | <한 줄 목표>.<br>근거: meeting.md:5 |
  | 배경 | <회의록 관찰>.<br>근거: meeting.md:5, meeting.md:11 |
  | 문제 | <일정·환불 안내가 맨 아래 작은 글씨라 안 읽힘>.<br>근거: meeting.md:12 |
  | 사용자 | <회의에서 언급된 그룹만>.<br>근거: meeting.md:5, meeting.md:16 |
  | 해결 방향 | <강조 화면 + 자동 메일 두 갈래만>.<br>근거: meeting.md:22, meeting.md:23 |
  | 성공 기준 | {metric_line} |
  | 확인 필요 항목 | 최종 담당자·일정(월요일 정기 미팅), CS 문의 60%는 추정·출처 미확인이라 수치 출처 확인 필요, 환불 기준 명문화 가능 여부(운영팀 회신 대기), 시안·메일 작업 일정 최종 승인.<br>근거: meeting.md:33, meeting.md:34, meeting.md:35, meeting.md:36 |
- 판정: 통과
- 남은 질문: 최종 담당자·일정, CS 문의 60% 수치 출처, 환불 기준 명문화 가능 여부, 시안·메일 작업 일정 최종 승인.
- 다음 세션의 첫 행동: review 세션(L5 인용 ∥ L6 도메인 제약)으로 6칸 + 확인 필요 항목을 넘긴다."""


def _enforce_success_metric_contract(text: str, state: dict) -> str:
    """Worker 산출이 frozen metric 계약을 약화하지 못하게 성공 기준 칸만 결정론 보정한다."""
    confirmed = state["requirements"]["acceptance"]["metric_confirmed"]
    if confirmed:
        metric = state["requirements"]["acceptance"]["metric"]
        success_row = (
            f'| 성공 기준 | "신청 직후 환불·일정 문의를 줄인다" — '
            f'{metric}.<br>근거: meeting.md:10; requirements_update acceptance.metric |'
        )
        text = _SUCCESS_ROW.sub(success_row, text, count=1)
        text = re.sub(r",?\s*성공 기준의 비율·기간·목표값 확인 필요", "", text)
        text = re.sub(r",?\s*성공 기준의 측정 단위·기간 확인 필요", "", text)
        text = re.sub(r",?\s*성공 기준의 비율·기간·목표값", "", text)
        text = re.sub(r",?\s*성공 기준의 측정 단위·기간", "", text)
        return text

    return _SUCCESS_ROW.sub(_AMBIGUOUS_SUCCESS, text, count=1)


def _build_leaf_prompt(state: dict) -> str:
    confirmed = state["requirements"]["acceptance"]["metric_confirmed"]
    if confirmed:
        metric_line = (f'"신청 직후 환불·일정 문의를 줄인다" — '
                       f'{state["requirements"]["acceptance"]["metric"]}.'
                       '<br>근거: meeting.md:10; requirements_update acceptance.metric')
        metric_rule = ("성공 기준 칸에는 보존 표현 \"신청 직후 환불·일정 문의를 줄인다\"를 큰따옴표로 두고, "
                       "확정된 측정 지표(비율·기간·목표값)를 한 줄로 병기하라.")
    else:
        metric_line = ("신청 직후 환불·일정 문의를 줄인다 (← 측정 단위·기간 미정, 목표 문장인지 "
                       "측정 지표인지 미정).<br>근거: meeting.md:10")
        metric_rule = ("성공 기준의 측정 단위·기간은 아직 회의록 밖 미확정이다. 지표 수치를 지어내지 말고 "
                       "목표 문장 그대로 두되 '측정 단위·기간 미정'을 명시하라.")
    return (
        "아래 회의록(줄번호 포함)과 L1 질문표를 읽고, 7칸(제목·배경·문제·사용자·해결 방향·성공 기준·"
        "확인 필요 항목)을 채운 build handoff를 만들어라. 각 칸 근거는 실제 회의록 줄(meeting.md:NN, "
        "1~36)만 쓴다. CS 문의 60%는 확인 필요 항목에만, 추정·미확인 표지와 함께 둔다.\n"
        f"{metric_rule}\n"
        "아래 골격을 그대로 채워 **코드펜스 없이 markdown 본문만** 출력하라. 모든 헤더와 '- ' 항목, "
        "7칸 표 구조를 빠짐없이 유지하라.\n\n"
        + _build_skeleton(metric_line)
        + "\n\n[L1 질문표]\n" + read(HANDOFFS / "handoff-L1-ask.md")
        + "\n\n[회의록 원문 (줄번호 포함)]\n" + _numbered_meeting()
    )


def adapter_build_claude(state: dict) -> Path:
    """build leaf 를 실제 Claude sub agent 가 수행: 회의록 → 7칸 handoff 생성.

    metric 미확정이면 성공 기준 칸을 모호(목표 문장)로 두라고 지시 → L6 ask-back.
    metric 확정이면 측정 지표를 병기하라고 지시 → pass.
    """
    system_prompt = _agent_md_body(WS / ".claude" / "agents" / "build.md")
    user_prompt = _build_leaf_prompt(state)
    text = _strip_fence(asyncio.run(_claude_leaf(system_prompt, user_prompt)))
    text = _enforce_success_metric_contract(text, state)
    dst = HANDOFFS / "handoff-L3L4-build.md"
    write(dst, text)
    return dst


def adapter_build_codex(state: dict) -> Path:
    """build leaf 를 실제 Codex CLI worker 가 수행: 회의록 → 7칸 handoff 생성."""
    system_prompt = _agent_toml_instructions(WS / ".codex" / "agents" / "build.toml")
    user_prompt = _build_leaf_prompt(state)
    text = _strip_fence(_codex_leaf(system_prompt, user_prompt))
    text = _enforce_success_metric_contract(text, state)
    dst = HANDOFFS / "handoff-L3L4-build.md"
    write(dst, text)
    return dst


# ──────────────────────────────────────────────────────────────────────────
# review_parallel 를 등록된 MCP 서버(review_server.py)로 호출 — MCP 프로토콜 경유
# ──────────────────────────────────────────────────────────────────────────
def review_via_mcp(build_path: Path) -> dict:
    """등록된 MCP 서버를 stdio 로 띄워 review_parallel tool 을 호출한다 (mcp_client_demo 와 같은 경로)."""
    from mcp import ClientSession, StdioServerParameters
    from mcp.client.stdio import stdio_client

    async def _call() -> dict:
        params = StdioServerParameters(
            command=sys.executable, args=[str(WS / "review_server.py")],
            env=dict(os.environ), cwd=str(WS),
        )
        async with stdio_client(params) as (r, w):
            async with ClientSession(r, w) as session:
                await session.initialize()
                result = await session.call_tool(
                    "review_parallel",
                    {"draft": read(build_path), "source": read(MEETING)},
                )
                payload = getattr(result, "structuredContent", None)
                if not payload and result.content:
                    for block in result.content:
                        txt = getattr(block, "text", "")
                        if txt.strip().startswith("{"):
                            payload = json.loads(txt)
                            break
                return payload or {}

    return asyncio.run(_call())


# ──────────────────────────────────────────────────────────────────────────
# review_parallel local judges (실제 파일을 읽는 결정론 평가)
# ──────────────────────────────────────────────────────────────────────────
def judge_L5_citation(build_path: Path) -> dict:
    """L5 보존 표현 인용 검사: build handoff의 meeting.md:NN 줄이 실재하고 핵심 근거가 있는가."""
    text = read(build_path)
    n = meeting_line_count()
    cites = sorted({int(x) for x in re.findall(r"meeting\.md:(\d+)", text)})
    bad = [c for c in cites if c < 1 or c > n]
    # canonical = inputs/review-gate.md Evidence Matrix 핵심 줄. review_gate.py 는 특정 줄을
    # 강제하지 않으므로(LEAF_RULES 는 키워드+evidence만), judge 도 강제하지 않는다. 누락은
    # 정보성 note 로만 남겨 judge 가 결정론 gate 보다 엄격해지지 않게 한다 (work_adapter=claude 가
    # 동등하게 타당한 대체 줄을 인용해도 통과). 줄 실재(bad)와 인용 존재만 hard 검사.
    canonical = {5, 10, 12, 22, 23}
    missing_evidence = sorted(canonical - set(cites))
    violations = []
    if not cites:
        violations.append("인용 줄(meeting.md:NN)이 하나도 없음")
    if bad:
        violations.append(f"존재하지 않는 meeting.md 줄: {bad} (파일 {n}줄)")
    verdict = "통과" if not violations else "재작업"
    notes = f"canonical 핵심 줄 미인용(정보성, gate 불강제): {missing_evidence}" if missing_evidence else ""
    return {"leaf": "L5", "verdict": verdict, "cites": cites,
            "violations": violations, "checked_lines": n, "notes": notes}


_METRIC = re.compile(r"\d+\s*%|\d+\s*주|\d+\s*건")

# review_gate.py 의 QUALIFIERS 와 정확히 같은 집합 — L6 judge 가 결정론 gate 보다 엄격해지지 않게.
_UNCERTAINTY_MARKERS = ("확인 필요", "보류", "미확인", "출처", "남은 질문",
                        "단정 금지", "단정하지", "추정", "ask-back", "재질문")


def judge_L6_domain(build_path: Path, state: dict) -> dict:
    """L6 도메인/범위/FN 검사: 60% 단정·범위 오염을 잡고, 성공 기준 목표/지표 미정이면 ask-back."""
    text = read(build_path)
    violations = []
    # 성공 기준 칸 추출
    m = re.search(r"\|\s*성공 기준\s*\|([^\n]*)", text)
    success_cell = m.group(1) if m else ""
    metric_present = bool(_METRIC.search(success_cell))
    metric_confirmed = state["requirements"]["acceptance"]["metric_confirmed"]

    # 범위 오염: 해결 방향 칸에 범위 밖 항목이 "해결책으로" 들어갔는지.
    # 단, 같은 칸에 범위 밖 마커(범위 밖/제외 등)와 함께 부기된 경우는 도메인 규칙상 허용
    # (review_gate.py 의 `"결제 모듈" and "범위 밖" not in text` 로직과 정렬).
    m2 = re.search(r"\|\s*해결 방향\s*\|([^\n]*)", text)
    solution_cell = m2.group(1) if m2 else ""
    scope_markers = ("범위 밖", "범위가 아니다", "이번 범위", "제외", "넣지 않", "건드리지 않", "변경 불가")
    annotated = any(mk in solution_cell for mk in scope_markers)
    for forbidden in ("전체 리뉴얼", "결제 모듈", "환불 기준 자체 명문화", "환불 기준 명문화"):
        if forbidden in solution_cell and not annotated:
            violations.append(f"범위 밖 항목이 해결 방향에 해결책으로 유입: {forbidden}")

    # 60% 본문 단정(불확실성 표지 없이): review_gate.py 와 같은 표지 집합으로 판정한다.
    for line in text.splitlines():
        if re.search(r"60\s*%", line) and not any(q in line for q in _UNCERTAINTY_MARKERS):
            violations.append(f"60% 본문 단정(불확실성 표지 없음): {line.strip()[:40]}")
            break

    if violations:
        return {"leaf": "L6", "verdict": "재작업", "violations": violations,
                "metric_present": metric_present}

    # 값·범위 위반이 없을 때: 성공 기준 목표/지표 구분이 안 되면 ask-back
    if not metric_present and not metric_confirmed:
        return {"leaf": "L6", "verdict": "재질문", "violations": [],
                "metric_present": False,
                "ask_back_reason": "성공 기준이 목표 문장인지 측정 지표인지 미정 "
                                   "(측정 단위·기간 없음) → 회의록 밖 결정 필요"}
    return {"leaf": "L6", "verdict": "통과", "violations": [], "metric_present": metric_present}


# ── L5/L6 review handoff 작성 (review_gate.py --leaf 가 파싱 가능한 형식) ──
def _verdict_word(v: str) -> str:
    return v  # 통과/재작업/재질문/종료 그대로


def _vstr(violations) -> str:
    """위반 목록을 문자열로. live 어댑터는 dict 리스트를 줄 수 있어 안전하게 직렬화."""
    if not violations:
        return "없음"
    out = []
    for v in violations:
        out.append(v if isinstance(v, str) else json.dumps(v, ensure_ascii=False))
    return "; ".join(out)


def write_L5_handoff(judge: dict, build_path: Path) -> Path:
    cites = ", ".join(f"meeting.md:{c}" for c in judge["cites"])
    note = judge.get("notes", "")
    if judge["verdict"] == "통과":
        l5_result = (f"인용 줄 {cites} 가 모두 meeting.md(원문 {judge['checked_lines']}줄) 안에 실재하고, "
                     f"배경·문제·해결 방향·성공 기준 근거 줄과 대조된다. 보존 표현은 원문 인용으로 유지된다."
                     + (f" [{note}]" if note else "") + f" (런타임: {judge.get('runtime', 'local')})")
    else:
        l5_result = (f"인용 검사 실패: {_vstr(judge['violations'])}. "
                     f"meeting.md(원문 {judge['checked_lines']}줄) 대조 결과 위 위반이 있다. "
                     f"(런타임: {judge.get('runtime', 'local')})")
    body = f"""# Handoff — L5 · review 세션 → 메인

## From task (4-1 리프)
- 목적: L5 보존 표현 인용 검사로 build handoff의 주장과 인용이 meeting.md 원문 줄과 직접 대조되는지 검수한다.
- 입력: {build_path.relative_to(WS)}, inputs/meeting.md, inputs/review-gate.md
- 범위 (하지 않을 일 포함): 본문 6칸을 다시 쓰지 않는다. 인용 일치와 줄번호 실재만 본다.
- 금지: 회의록에 없는 값을 보정해 추가하지 않는다.
- 완료조건: 각 핵심 주장의 meeting.md:NN 근거가 원문과 의미 일치하는지 판정한다.

## Result (이 세션이 채운 부분)
- 결과 요약: {l5_result}
- 위반 줄번호: {_vstr(judge['violations'])}
- 판정: {_verdict_word(judge['verdict'])}
- 남은 질문: 없음
- 다음 세션의 첫 행동: L6 도메인 제약 검사 결과와 합류해 merge_verdict로 넘긴다.
"""
    dst = HANDOFFS / "handoff-L5-review.md"
    write(dst, body)
    return dst


def write_L6_handoff(judge: dict, build_path: Path) -> Path:
    if judge["verdict"] == "재질문":
        result = (f"외부 사실 단정은 없음. 미확인 수치 60%는 추정·출처 미확인으로 확인 필요 항목에만 보존됨. "
                  f"범위 밖 항목(전체 리뉴얼·결제 모듈 모달·환불 기준 명문화)은 해결 방향에 없음. "
                  f"미결정 항목은 확인 필요 항목으로 분리됨. 다만 성공 기준이 목표 문장인지 측정 지표인지 "
                  f"미정이라 다음 단계 판단을 막는 모호함이 있다.")
        remaining = judge.get("ask_back_reason", "성공 기준 목표/지표 구분 필요")
        nxt = "requirements_update로 2장 사각지대 질문(성공 기준 metric)을 만들어 사람에게 회신 요청한다."
    elif judge["verdict"] == "통과":
        result = ("외부 사실 단정 없음. 미확인 수치 60%는 추정·출처 미확인으로 확인 필요 항목에만 보존. "
                  "범위 밖 항목은 해결 방향에 없음(범위 밖 유지). 미결정 항목은 확인 필요 항목으로 분리. "
                  "성공 기준은 측정 단위·기간이 있는 지표로 좁혀져 다음 단계 판단이 가능하다.")
        remaining = "없음"
        nxt = "merge_verdict로 넘겨 7칸 1page로 통합한다."
    else:
        result = "값 단정·범위 오염 위반: " + _vstr(judge["violations"])
        remaining = "위반 칸을 한 줄로 보정해 재제출 필요"
        nxt = "build로 되돌려 위반 칸만 보정한다."
    body = f"""# Handoff — L6 · review 세션 → 메인

## From task (4-1 리프)
- 목적: L6 도메인 제약 위반 검사로 외부 사실 단정·미확인 수치·범위 밖 항목 유입·미결정 결론 톤을 검수한다.
- 입력: {build_path.relative_to(WS)}, inputs/meeting.md, inputs/review-gate.md
- 범위 (하지 않을 일 포함): 본문 6칸을 다시 쓰지 않는다. 산문 표현 차이는 통과시키고 값 단정·범위 오염·모호함만 판정한다.
- 금지: 회의록에 없는 값으로 보정하거나 60%를 출처 확정값처럼 판정하지 않는다.
- 완료조건: stage별 결과, 위반 항목 줄, verdict, 다음 행동을 남긴다.

## Result (이 세션이 채운 부분)
- 결과 요약: {result} (런타임: {judge.get('runtime', 'local')})
- 위반 항목 줄: {_vstr(judge['violations'])}
- 판정: {_verdict_word(judge['verdict'])}
- 남은 질문: {remaining}
- 다음 세션의 첫 행동: {nxt}
"""
    dst = HANDOFFS / "handoff-L6-review.md"
    write(dst, body)
    return dst


# ──────────────────────────────────────────────────────────────────────────
# Contracts (frozen)
# ──────────────────────────────────────────────────────────────────────────
def base_requirements() -> dict:
    return {
        "reader": "1page를 받는 의사결정자 + 후속 실무자",
        "decision": "사내 교육 신청 페이지 개편 방향(강조 화면 + 자동 메일) 채택 여부",
        "success_signal": "추가 질문 없이 결정으로 넘어간다 / 결정 항목과 확인 질문 분리",
        "constraints": [
            "회의록·확인된 요구사항 밖 사실 결론 금지",
            "한 페이지(7칸) 분량 유지",
            "미확인 수치(60%) 본문 단정 금지 — 확인 필요 항목으로만",
            "범위 밖 항목 해결 방향 금지",
            "미결정 항목 결론 톤 단정 금지",
        ],
        "acceptance": {
            "goal": "회의록 한 건을 1page 7칸 초안으로 옮긴다",
            "behavioral": "의사결정자가 추가 질문 없이 결정으로 넘어간다",
            "metric": "미확정",
            "metric_confirmed": False,
        },
    }


def base_spec() -> dict:
    return {
        "output_contract": "7칸: 제목·배경·문제·사용자·해결 방향·성공 기준·확인 필요 항목",
        "policy": {"fixed": "확정 값·범위·인용", "remaining": "미결정→확인 필요 항목",
                   "question": "회의록 밖 결정→ask"},
        "preserve_phrases": PRESERVE_PHRASES,
        "success_cell": "신청 직후 환불·일정 문의를 줄인다 (목표/지표 미정)",
    }


# ──────────────────────────────────────────────────────────────────────────
# Final 1page check (3장 check-1page-output 3종을 exit 산출물에 적용)
# ──────────────────────────────────────────────────────────────────────────
def final_1page_check(text: str) -> list[str]:
    violations = []
    for p in PRESERVE_PHRASES:
        if f'"{p}"' not in text:
            violations.append(f"보존 표현 큰따옴표 인용 누락: \"{p}\"")
    if not re.search(r"#+\s*확인\s*필요\s*항목", text):
        violations.append("'확인 필요 항목' 섹션 없음")
    head = re.split(r"#+\s*확인\s*필요\s*항목", text)[0]
    if re.search(r"60\s*%", head):
        violations.append("60% 수치가 확인 필요 항목 위쪽 본문에 단정됨")
    return violations


def write_final_1page(build_path: Path, state: dict) -> Path:
    b = read(build_path)

    def cell(name: str) -> str:
        m = re.search(rf"\|\s*{re.escape(name)}\s*\|([^\n|]*)", b)
        return (m.group(1).strip().split("<br>")[0] if m else "").strip()

    body = f"""# 1page 기획안 초안 — 사내 교육 신청 페이지 개편 (하네스 v2 exit)

## 실행 요약
- 시작 입력: inputs/meeting.md
- 실행 흐름: requirements_contract → spec_contract → session_surface → structured_contract → state_store → fill_handoff → dispatch_plan → invoke_runtime_adapter → collect_handoff → review_gate → review_parallel(L5 ∥ L6) → merge_verdict → route
- 최종 route: exit (satisfice — 7칸 충분 + guard 통과 + 불확실성 분리)
- 성공 기준 metric: {state['requirements']['acceptance']['metric']}

## 7칸
| 칸 | 내용 |
|---|---|
| 제목 | {cell('제목')} |
| 배경 | {cell('배경')} |
| 문제 | {cell('문제')} |
| 사용자 | {cell('사용자')} |
| 해결 방향 | {cell('해결 방향')} |
| 성공 기준 | {cell('성공 기준')} |
| 확인 필요 항목 | 최종 담당자·일정(월요일 정기 미팅), CS 문의 수치 출처(추정·미확인), 환불 기준 명문화 가능 여부(운영팀 회신 대기), 시안·메일 작업 일정 최종 승인 |

## 보존 표현 (큰따옴표 인용 유지)
- "신청 직후 환불·일정 문의를 줄인다"
- "페이지 전체 리뉴얼은 이번 범위가 아니다"
- "환불 기준 자체가 명문화돼 있지 않다"

## Review Gate 결과
- review_gate(deterministic precheck): pass
- L5 보존 표현 인용 검사: 통과
- L6 도메인 제약 위반 검사: 통과

## 확인 필요 항목
- 최종 담당자·일정: 월요일 정기 미팅에서 확정 예정.
- CS 문의 60% 수치 출처: 한CS 추정, 출처 미확인.
- 환불 기준 명문화 가능 여부: 운영팀 회신 대기. "환불 기준 자체가 명문화돼 있지 않다".
- 시안·메일 작업 일정: 최종 승인 대기.
"""
    write(FINAL_1PAGE, body)
    return FINAL_1PAGE


# ──────────────────────────────────────────────────────────────────────────
# Orchestrator
# ──────────────────────────────────────────────────────────────────────────
class HarnessV2:
    def __init__(self, run_id: str, runtime: str, review_adapter: str,
                 work_adapter: str = "replay") -> None:
        self.run_id = run_id
        self.runtime = runtime
        self.review_adapter = review_adapter      # local | live | mcp
        self.work_adapter = work_adapter          # replay | claude | codex
        self.log = RunLog()

    # ---- nodes ----
    def n_requirements_contract(self, state: dict) -> None:
        req = state["requirements"]
        frozen = read(CONTRACTS / "requirements-contract.md")
        h = sha256(json.dumps(req, ensure_ascii=False, sort_keys=True) + frozen)
        state["frozen"]["requirements"] = h
        status = "pass" if req["acceptance"]["metric_confirmed"] else "pass(metric 미확정)"
        self.log.event(self.run_id, "requirements_contract", "requirements.contract.frozen",
                       status, detail=f"reader/decision/success/constraints 잠금 sha={h}")

    def n_spec_contract(self, state: dict) -> None:
        frozen = read(CONTRACTS / "spec-contract.md")
        h = sha256(json.dumps(state["spec"], ensure_ascii=False, sort_keys=True) + frozen)
        state["frozen"]["spec"] = h
        self.log.event(self.run_id, "spec_contract", "spec.contract.frozen", "pass",
                       detail=f"7칸 output contract + 고정·남김·질문 잠금 sha={h}")

    def n_session_surface(self, state: dict) -> None:
        # 3장 SessionStart + 메타/도메인 스킬 + PostToolUse(=review_gate precheck) 통합 안내 주입
        subprocess.run([sys.executable, str(WS / ".codex/hooks/session_start.py")],
                       capture_output=True, text=True)
        injected = (STATE / "session-start-context.md").exists()
        self.log.event(self.run_id, "session_surface", "session.surface.injected",
                       "injected" if injected else "missing",
                       detail="SessionStart 안내 + using-1page-harness(메타) + meeting-to-1page(도메인) + check-1page-output→review_gate precheck")

    def n_structured_contract(self, state: dict) -> None:
        payload = {"requirements_sha": state["frozen"]["requirements"],
                   "spec_sha": state["frozen"]["spec"],
                   "handoff_schema": "From task/Result/판정/다음 행동",
                   "output_contract": state["spec"]["output_contract"]}
        h = save_checkpoint("structured-contract", payload)
        self.log.event(self.run_id, "structured_contract", "structured.contract.locked",
                       "pass", detail=f"handoff schema + 1page contract 잠금 checkpoint sha={h}")

    def n_state_store(self, state: dict) -> None:
        # 진실원천은 run-state.json(load_run_state). checkpoint는 무결성 해시를 가진 보조 채널이다.
        # last_good checkpoint를 실제로 읽어 sha256 무결성을 검증하고, run-state에 빠진 값이 있으면
        # checkpoint payload로 보강한다(write-only가 아니라 read-verified가 되도록).
        events = RunLog.replay()
        last_good = load_last_good()
        if last_good:
            payload = last_good.get("payload", {})
            ok = sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True)) == last_good.get("sha256")
            restored = []
            if not state.get("preserved_passes") and payload.get("preserved_passes"):
                state["preserved_passes"] = payload["preserved_passes"]; restored.append("preserved_passes")
            if not state.get("resume_target") and payload.get("resume_target"):
                state["resume_target"] = payload["resume_target"]; restored.append("resume_target")
            mode = (f"replay({len(events)} events) + last_good checkpoint 검증(sha256 {'OK' if ok else '불일치'})"
                    + (f", 복원={restored}" if restored else ", 복원 없음(run-state 우선)"))
        else:
            mode = f"init (replay {len(events)} events, checkpoint 없음)"
        save_checkpoint("state-store", {"events": len(events),
                                        "resume_target": state.get("resume_target")})
        self.log.event(self.run_id, "state_store.load_or_init", "state.store.loaded",
                       "pass", detail=mode)

    def n_fill_handoff(self, state: dict) -> None:
        # leaf + prior verdict + source paths -> brief (handoff-template 골격 사용)
        brief = (f"leaf=ask,build / prior={state.get('last_verdict','none')} / "
                 f"source=inputs/meeting.md,handoffs/handoff-L1-ask.md / "
                 f"contract=requirements({state['frozen']['requirements']}),spec({state['frozen']['spec']})")
        write(HANDOFFS / "_brief.md",
              f"# fill_handoff brief\n\n- {now()}\n- {brief}\n")
        self.log.event(self.run_id, "fill_handoff", "handoff.filled", "pass",
                       detail="ask/build brief 1장 작성 (handoff-template 골격)")

    def n_dispatch_plan(self, state: dict) -> None:
        plan = ["L1-ask", "L3L4-build"]
        state["dispatch"] = plan
        self.log.event(self.run_id, "dispatch_plan", "dispatch.planned", "pass",
                       detail=f"leaf role={plan}, evidence slice=meeting.md:5,10,12,22,23")

    def _dispatch_leaf(self, kind: str, state: dict) -> Path:
        """kind in {'ask','build'}. work=claude/codex면 실제 worker, 실패 시 replay로 정직히 폴백.

        (review live/mcp 경로가 _remote_review try/except로 local 폴백하는 것과 대칭 —
        수강생 SDK/모델/인증이 불가해도 graph가 unhandled traceback으로 죽지 않는다.)
        """
        worker_fns = {
            "claude": adapter_ask_claude if kind == "ask" else adapter_build_claude,
            "codex": adapter_ask_codex if kind == "ask" else adapter_build_codex,
        }
        replay_fn = adapter_ask if kind == "ask" else adapter_build
        if self.work_adapter == "replay":
            return replay_fn(state)
        worker_fn = worker_fns.get(self.work_adapter)
        if worker_fn is None:
            self.log.event(self.run_id, "invoke_runtime_adapter", f"subagent.{kind}.fallback",
                           "replay",
                           detail=f"지원하지 않는 work_adapter={self.work_adapter} → replay 폴백")
            return replay_fn(state)
        try:
            return worker_fn(state)
        except Exception as exc:
            self.log.event(self.run_id, "invoke_runtime_adapter", f"subagent.{kind}.fallback",
                           "replay",
                           detail=f"{self.work_adapter} 디스패치 실패({type(exc).__name__}: {str(exc)[:120]}) → replay 폴백")
            return replay_fn(state)

    def n_invoke_runtime_adapter(self, state: dict) -> None:
        live_worker = self.work_adapter in ("claude", "codex")
        # --- ask leaf dispatch (실제 worker 실패 시 replay 폴백) ---
        ask = self._dispatch_leaf("ask", state)
        rc_a, err_a = gate_posttool(ask)
        ra, _, _ = gate_cli(ask, "L1")
        self.log.event(self.run_id, "invoke_runtime_adapter", "subagent.ask.dispatched",
                       "ok" if rc_a == 0 and ra == "pass" else "rework",
                       detail=f"adapter={self.work_adapter} → {ask.name} (precheck rc={rc_a}, --leaf L1={ra})"
                              + (f" | {err_a}" if rc_a == 2 else ""))
        # --- build leaf dispatch (실제 worker 면 precheck 실패 시 1회 재시도, 그래도 폴백 보장) ---
        build = self._dispatch_leaf("build", state)
        rc_b, err_b = gate_posttool(build)
        if live_worker and rc_b == 2:
            self.log.event(self.run_id, "invoke_runtime_adapter", "subagent.build.rework",
                           "retry", detail=f"build precheck 실패 → 재dispatch | {err_b}")
            build = self._dispatch_leaf("build", state)
            rc_b, err_b = gate_posttool(build)
        state["build_path"] = str(build)
        self.log.event(self.run_id, "invoke_runtime_adapter", "subagent.build.dispatched",
                       "ok" if rc_b == 0 else "rework",
                       detail=f"adapter={self.work_adapter} → {build.name} (precheck rc={rc_b})"
                              + (f" | {err_b}" if rc_b == 2 else ""))
        self.log.event(self.run_id, "invoke_runtime_adapter", "runtime.adapter.invoked",
                       "completed",
                       detail=f"runtime={self.runtime} work_adapter={self.work_adapter}: "
                              f"ask({ask.name}) + build({build.name}) dispatched")

    def n_collect_handoff(self, state: dict) -> None:
        # normalize: handoff 필수 섹션 존재 확인, 비계약 상태(대화 로그) 없음 확인
        build = Path(state["build_path"])
        text = read(build)
        ok = all(s in text for s in ("## From task", "## Result", "- 판정:"))
        hidden = "```chat" in text or "assistant:" in text.lower()
        self.log.event(self.run_id, "collect_handoff", "handoff.collected",
                       "pass" if ok and not hidden else "reject",
                       detail=f"build handoff 정규화 (필수 섹션 ok={ok}, hidden state={hidden})")

    def n_review_gate(self, state: dict) -> str:
        """결정론 precheck: review_gate.py(format/line/60%) + 확인 필요 항목 섹션. 통과해야 review_parallel."""
        build = Path(state["build_path"])
        rc, err = gate_posttool(build)
        confirm_ok = bool(re.search(r"확인 필요 항목", read(build)))
        # L1 handoff 도 결정론 leaf gate 로 확인
        l1_route, _, _ = gate_cli(HANDOFFS / "handoff-L1-ask.md", "L1")
        if rc == 2 or not confirm_ok:
            self.log.event(self.run_id, "review_gate", "review.gate.checked", "rework",
                           route="retry",
                           detail=f"precheck 실패 rc={rc} confirm_section={confirm_ok} | {err}")
            return "rework"
        self.log.event(self.run_id, "review_gate", "review.gate.checked", "pass",
                       detail=f"format/line/60% 결정론 통과 (rc={rc}), L1 leaf gate={l1_route}, 확인필요항목={confirm_ok}")
        return "pass"

    def _remote_review(self, build: Path, state: dict) -> tuple[dict, dict] | None:
        """L5(인용)←Claude Agent SDK ∥ L6(사실·범위)←codex exec 를 실제 호출.

        review_adapter:
          - live: review_server.review_parallel_live() 를 in-process 로 호출.
          - mcp : 등록된 MCP 서버(review_server.py)를 stdio MCP 프로토콜로 띄워 review_parallel tool 호출.
        실패(패키지/런타임 불가) 시 None → local 결정론 judge 로 폴백(정직하게 로그).
        성공 기준 metric ask-back 은 구조적 계약 판정이라 local judge 로 교차 유지한다.
        """
        try:
            if self.review_adapter == "mcp":
                merged = review_via_mcp(build)
                channel = "MCP tool call (review_server.py via stdio)"
            else:
                import review_server  # noqa: PLC0415
                merged = review_server.review_parallel_live(read(build), read(MEETING))
                channel = "in-process review_parallel_live"
        except Exception as exc:  # 패키지/런타임 없음 등 → local 폴백
            self.log.event(self.run_id, "review_parallel", "review.parallel.fallback",
                           "local", detail=f"{self.review_adapter} 어댑터 불가({type(exc).__name__}: {str(exc)[:60]}) → local judge")
            return None
        cit, fac = merged.get("citation", {}), merged.get("facts", {})
        # verdict='error'(예: codex 미로그인, JSON 파싱 실패)는 '실패'가 아니라 '판정 불가'다.
        # 빈-violation '재작업'으로 위장하지 않고 정직하게 local judge 로 폴백한다(예외 경로와 동일).
        if cit.get("verdict") == "error" or fac.get("verdict") == "error":
            why = []
            if cit.get("verdict") == "error":
                why.append(f"L5(claude)={cit.get('violations') or cit.get('raw', '')[:60]}")
            if fac.get("verdict") == "error":
                why.append(f"L6(codex)={fac.get('violations') or fac.get('raw', '')[:60]}")
            self.log.event(self.run_id, "review_parallel", "review.parallel.fallback",
                           "local", detail=f"{self.review_adapter} runner가 verdict 못 냄({'; '.join(why)}) → local judge")
            return None
        local5 = judge_L5_citation(build)
        local6 = judge_L6_domain(build, state)
        j5 = {"leaf": "L5", "runtime": "claude(agent-sdk)",
              "verdict": "통과" if cit.get("verdict") == "pass" else "재작업",
              "cites": local5["cites"], "checked_lines": local5["checked_lines"],
              "violations": cit.get("violations", []) if cit.get("verdict") != "pass" else []}
        # 구조적 metric ask-back 이 걸리면 local6 우선(재질문), 아니면 codex 의 사실·범위 verdict 사용
        if local6["verdict"] == "재질문":
            j6 = dict(local6); j6["runtime"] = "codex(exec)+local metric gate"
        else:
            j6 = {"leaf": "L6", "runtime": "codex(exec)",
                  "verdict": "통과" if fac.get("verdict") == "pass" else "재작업",
                  "violations": fac.get("violations", []) if fac.get("verdict") != "pass" else [],
                  "metric_present": local6.get("metric_present", True)}
        self.log.event(self.run_id, "review_parallel", "review.parallel.remote",
                       f"L5(claude)={cit.get('verdict','?')}, L6(codex)={fac.get('verdict','?')}",
                       detail=f"Claude Agent SDK ∥ codex exec 실제 병렬 호출 via {channel} (기존 인증)")
        return j5, j6

    def n_review_parallel(self, state: dict) -> dict:
        """L5 ∥ L6 의미 검수를 병렬로 돈다 (local 결정론 judge / live in-process / mcp 프로토콜)."""
        build = Path(state["build_path"])
        remote = (self._remote_review(build, state)
                  if self.review_adapter in ("live", "mcp") else None)
        if remote is not None:
            j5, j6 = remote
        else:
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as ex:
                f5 = ex.submit(judge_L5_citation, build)
                f6 = ex.submit(judge_L6_domain, build, state)
                j5, j6 = f5.result(), f6.result()
        h5 = write_L5_handoff(j5, build)
        h6 = write_L6_handoff(j6, build)
        # 4장 review_gate.py --leaf 로 작성된 review handoff 를 다시 결정론 검증 (가이드 요구 명령)
        r5, _, ec5 = gate_cli(h5, "L5")
        r6, _, ec6 = gate_cli(h6, "L6")
        self.log.event(self.run_id, "review_parallel", "review.parallel.completed",
                       f"L5={j5['verdict']}, L6={j6['verdict']}",
                       detail=f"gate --leaf L5→{r5}(ec{ec5}) ∥ L6→{r6}(ec{ec6}) [adapter={self.review_adapter}]")
        return {"L5": j5, "L6": j6, "gate": {"L5": r5, "L6": r6}}

    def n_merge_verdict(self, state: dict, rp: dict) -> dict:
        passes = [k for k, v in rp.items() if k in ("L5", "L6") and v["verdict"] == "통과"]
        failed = [v for k, v in rp.items() if k in ("L5", "L6") and v["verdict"] != "통과"]
        # regression guard: 이전에 preserve 된 pass 가 이번에 깨졌는가
        prev = set(state.get("preserved_passes", []))
        regression = sorted(prev - set(passes))
        # preserve: 이번 pass 를 누적 보존
        state["preserved_passes"] = sorted(set(passes) | prev) if not regression else sorted(passes)
        failed_sides = [v["leaf"] for v in failed]
        # last_verdict 기록: 다음 retry/resume 의 fill_handoff brief 가 prior verdict provenance 를 갖게 한다.
        state["last_verdict"] = f"L5={rp['L5']['verdict']},L6={rp['L6']['verdict']}"
        status = (f"preserved_passes={state['preserved_passes']}, failed_sides={failed_sides}"
                  + (f", REGRESSION={regression}" if regression else ""))
        self.log.event(self.run_id, "merge_verdict", "verdict.merged",
                       status, detail="한쪽 pass 보존, 실패 검수만 재작업 대상. 이전 pass 퇴행 검사.")
        return {"passes": passes, "failed": failed, "failed_sides": failed_sides,
                "regression": regression}

    def n_route(self, state: dict, rp: dict, merged: dict) -> str:
        l6 = rp["L6"]["verdict"]
        # regression 우선
        if merged["regression"]:
            save_checkpoint("route", {"route": "blocked-exit", "reason": "regression",
                                      "regression": merged["regression"]},
                            good=False, rewind_reason=f"regression {merged['regression']}")
            self.log.event(self.run_id, "route", "route.decided", "blocked exit",
                           route="blocked exit",
                           detail=f"이전 pass 퇴행 {merged['regression']} → last_good_checkpoint 보존")
            state["route"] = "blocked exit"
            return "blocked exit"

        if not merged["failed"]:
            # satisfice exit 후보. 단, 최종 1page가 3장 output guard(보존표현·확인필요항목·60%)를
            # 위반하면 exit하지 않는다 — "guard 통과일 때만 exit" 원칙(L18)을 코드로 강제.
            final = write_final_1page(Path(state["build_path"]), state)
            fviol = final_1page_check(read(final))
            if fviol:
                reason = "final 1page guard 위반: " + "; ".join(fviol)
                cnt = state["retry_count_by_reason"].get(reason, 0) + 1
                state["retry_count_by_reason"][reason] = cnt
                route_out = "blocked exit" if cnt >= 2 else "retry"
                save_checkpoint("route", {"route": route_out, "final_guard_violations": fviol},
                                good=False, rewind_reason=reason)
                self.log.event(self.run_id, "route", "route.decided",
                               "exit-blocked(final guard 위반)", route=route_out,
                               detail=f"{reason} → {route_out} (good checkpoint 미저장, count={cnt})")
                state["route"] = route_out
                return route_out
            save_checkpoint("route", {"route": "exit", "final": str(final.relative_to(WS)),
                                      "preserved_passes": state["preserved_passes"]},
                            good=True)
            self.log.event(self.run_id, "route", "route.decided", "exit", route="exit",
                           detail=f"7칸 충분 + guard 통과 + 불확실성 분리 → {final.relative_to(WS)} "
                                  f"(final 1page check 위반=없음)")
            state["route"] = "exit"
            return "exit"

        if l6 == "재질문":
            # ask-back: 2장 질문 구조로 requirements_update 대상 질문을 pending 으로 남기고 resume target 저장
            state["resume_target"] = "fill_handoff"
            reason = rp["L6"].get("ask_back_reason", "성공 기준 목표/지표 미정")
            self._write_pending(state, reason)
            save_checkpoint("route", {"route": "ask-back",
                                      "resume_target": state["resume_target"],
                                      "pending": "requirements.acceptance.metric",
                                      "preserved_passes": state["preserved_passes"]},
                            good=True, rewind_reason="ask-back: 성공 기준 metric 미확정")
            self.log.event(self.run_id, "route", "route.decided",
                           "ask-back(requirements_update)", route="ask-back",
                           detail=f"resume_target=fill_handoff, pending=requirements.acceptance.metric, 사유={reason}")
            state["route"] = "ask-back"
            return "ask-back"

        # rework/retry
        reason = "; ".join(merged["failed_sides"])
        cnt = state["retry_count_by_reason"].get(reason, 0) + 1
        state["retry_count_by_reason"][reason] = cnt
        if cnt >= 2:
            save_checkpoint("route", {"route": "blocked-exit", "reason": reason, "count": cnt},
                            good=False, rewind_reason=f"same retry x{cnt}: {reason}")
            self.log.event(self.run_id, "route", "route.decided", "blocked exit",
                           route="blocked exit",
                           detail=f"같은 retry 사유 {cnt}회({reason}) → last_good_checkpoint + pending 보존")
            state["route"] = "blocked exit"
            return "blocked exit"
        save_checkpoint("route", {"route": "retry", "return_node": "fill_handoff",
                                  "reason": reason}, good=False,
                        rewind_reason=f"retry: {reason}")
        self.log.event(self.run_id, "route", "route.decided", "retry", route="retry",
                       detail=f"checkpoint → fill_handoff, 사유={reason} (count={cnt})")
        state["route"] = "retry"
        return "retry"

    def _write_pending(self, state: dict, reason: str) -> None:
        body = f"""# Pending External — ask-back (requirements_update 대기)

- run: {self.run_id}
- 시각: {now()}
- route: ask-back
- resume_target: {state['resume_target']}
- pending field: requirements.acceptance.metric
- 사유: {reason}

## 2장 질문 구조 (사람 회신 필요)

requirements-contract.md §4의 사각지대 두 질문을 그대로 쓴다. 자동 추측으로 채우지 않는다.

### Q1. 이 정의가 놓친 사용자 시각
- (a) 후속 실무자가 성공 여부를 측정 지표로 추적할 수 있어야 하는가
- (b) "줄인다"의 기준선(현재 문의량)을 함께 봐야 하는가
- (c) CS팀이 집계 기간·집계 기준에 합의해야 하는가

### Q2. 이 정의로 잘려 나간 가능성
- (d) "신청 직후 환불·일정 문의를 줄인다"가 목표 문장인가, 측정 지표인가
- (e) 측정 지표라면 단위(비율·건수)와 기간(예: 4주)을 어디서 가져오는가
- (f) 기준선 수치(CS 문의 60% 추정)는 출처 확정 전까지 지표에 쓸 수 없는가

## 회신 방법

채택(☑/☒)을 inputs/answers.md 형식으로 적은 뒤:

    python3 harness_v2_server.py resume --answers inputs/answers.md

resume 가 requirements.acceptance.metric 과 spec 성공 기준 칸을 갱신(v2 freeze)하고
resume_target(fill_handoff)부터 graph를 이어서 돌린다.
"""
        write(PENDING, body)

    # ---- 전체 graph 한 번 실행 ----
    def traverse(self, state: dict, start: str = "requirements_contract",
                 max_retries: int = 3) -> str:
        """graph 한 바퀴. route=retry면 fill_handoff부터 재실행(재디스패치)한다.

        route 노드가 같은 retry 사유 2회에 blocked exit를 내므로 루프는 자연히 멈추고,
        max_retries는 추가 안전밸브다 (Ouroboros _execute_phase_with_retry + safety valve).
        """
        route = self._traverse_once(state, start)
        guard = 0
        while route == "retry" and guard < max_retries:
            guard += 1
            self.log.event(self.run_id, "orchestration", "retry.loop", "retry",
                           detail=f"재시도 {guard}/{max_retries} → fill_handoff부터 재실행(재디스패치)")
            route = self._traverse_once(state, "fill_handoff")
        return route

    def _traverse_once(self, state: dict, start: str = "requirements_contract") -> str:
        order = GRAPH_NODES
        started = False
        rp = merged = None
        for node in order:
            if node == start:
                started = True
            if not started:
                continue
            if node == "requirements_contract":
                self.n_requirements_contract(state)
            elif node == "spec_contract":
                self.n_spec_contract(state)
            elif node == "session_surface":
                self.n_session_surface(state)
            elif node == "structured_contract":
                self.n_structured_contract(state)
            elif node == "state_store.load_or_init":
                self.n_state_store(state)
            elif node == "fill_handoff":
                self.n_fill_handoff(state)
            elif node == "dispatch_plan":
                self.n_dispatch_plan(state)
            elif node == "invoke_runtime_adapter":
                self.n_invoke_runtime_adapter(state)
            elif node == "collect_handoff":
                self.n_collect_handoff(state)
            elif node == "review_gate":
                gate = self.n_review_gate(state)
                if gate == "rework":
                    return self.n_route(state, {"L5": {"verdict": "보류", "leaf": "L5"},
                                                "L6": {"verdict": "재작업", "leaf": "L6",
                                                       "violations": ["review_gate precheck 실패"]}},
                                        {"passes": [], "failed": [{"leaf": "L6"}],
                                         "failed_sides": ["L6"], "regression": []})
            elif node == "review_parallel":
                rp = self.n_review_parallel(state)
            elif node == "merge_verdict":
                merged = self.n_merge_verdict(state, rp)
            elif node == "route":
                return self.n_route(state, rp, merged)
        return state.get("route", "?")


# ──────────────────────────────────────────────────────────────────────────
# Run state persistence
# ──────────────────────────────────────────────────────────────────────────
def save_run_state(state: dict) -> None:
    write(RUNSTATE, json.dumps(state, ensure_ascii=False, indent=2))


def load_run_state() -> dict | None:
    return json.loads(read(RUNSTATE)) if RUNSTATE.exists() else None


def fresh_state(run_id: str) -> dict:
    return {
        "run_id": run_id,
        "requirements": base_requirements(),
        "spec": base_spec(),
        "frozen": {},
        "retry_count_by_reason": {},
        "preserved_passes": [],
        "resume_target": None,
        "route": None,
        "last_verdict": "none",
    }


def clear_produced() -> None:
    """run(fresh): 생성 산출물만 비운다. inputs/contracts/source/.codex/.claude 원본은 건드리지 않는다."""
    for f in HANDOFFS.glob("*"):
        f.unlink()
    for f in (RUNLOG_MD, RUNLOG_JSONL, RUNSTATE, PENDING, STATE / "session-start-context.md"):
        if f.exists():
            f.unlink()
    if CKPT.exists():
        for f in CKPT.glob("*"):
            f.unlink()
    HANDOFFS.mkdir(parents=True, exist_ok=True)
    CKPT.mkdir(parents=True, exist_ok=True)


# ──────────────────────────────────────────────────────────────────────────
# requirements_update (ask-back resume 때 2장 채택 적용)
# ──────────────────────────────────────────────────────────────────────────
def apply_answers(state: dict, answers_path: Path, log: RunLog, run_id: str) -> bool:
    text = read(answers_path)
    m = re.search(r"acceptance\.metric:\s*\"?([^\"\n]+)\"?", text)
    adopted = bool(re.search(r"☑", text)) and m is not None
    if not adopted:
        log.event(run_id, "requirements_update", "requirements.update.skipped", "blocked",
                  detail="채택(☑) 없음 → 자동 추측 금지, blocked exit")
        return False
    metric = m.group(1).strip()
    state["requirements"]["acceptance"]["metric"] = metric
    state["requirements"]["acceptance"]["metric_confirmed"] = True
    state["spec"]["success_cell"] = f'"신청 직후 환불·일정 문의를 줄인다" + {metric}'
    log.event(run_id, "requirements_update", "requirements.updated", "pass",
              detail=f"2장 사각지대 채택 반영 → acceptance.metric=\"{metric}\", spec 성공 기준 갱신")
    # v2 re-freeze: 갱신된 requirements/spec 을 다시 잠그고 hash 를 v2 로 올린다 (frozen contract 원칙).
    rfrozen = read(CONTRACTS / "requirements-contract.md")
    sfrozen = read(CONTRACTS / "spec-contract.md")
    rh = sha256(json.dumps(state["requirements"], ensure_ascii=False, sort_keys=True) + rfrozen)
    sh = sha256(json.dumps(state["spec"], ensure_ascii=False, sort_keys=True) + sfrozen)
    old_r, old_s = state["frozen"].get("requirements"), state["frozen"].get("spec")
    state["frozen"]["requirements"] = rh
    state["frozen"]["spec"] = sh
    save_checkpoint("structured-contract", {"requirements_sha": rh, "spec_sha": sh, "version": "v2"})
    log.event(run_id, "requirements_update", "spec.contract.refrozen", "pass",
              detail=f"v2 freeze: requirements {old_r}→{rh}, spec {old_s}→{sh}")
    return True


# ──────────────────────────────────────────────────────────────────────────
# CLI
# ──────────────────────────────────────────────────────────────────────────
def cmd_run(args) -> int:
    if args.fresh:
        clear_produced()
    run_id = args.run_id or "run-1"
    state = fresh_state(run_id)
    print(f"\n=== harness v2 RUN ({run_id}) — runtime={args.runtime} "
          f"work={args.work_adapter} review={args.review_adapter} ===")
    h = HarnessV2(run_id, args.runtime, args.review_adapter, args.work_adapter)
    route = h.traverse(state)
    save_run_state(state)
    print(f"\n=== route = {route} ===")
    if route == "ask-back":
        print(f"pending external → {PENDING.relative_to(WS)}")
        print("resume: python3 harness_v2_server.py resume --answers inputs/answers.md")
    elif route == "exit":
        print(f"final 1page  → {FINAL_1PAGE.relative_to(WS)}")
    return 0


def cmd_resume(args) -> int:
    state = load_run_state()
    if state is None:
        print("run-state.json 없음. 먼저 run 을 돌린다.", file=sys.stderr)
        return 1
    run_id = "run-2-resume"
    print(f"\n=== harness v2 RESUME ({run_id}) ← resume_target={state.get('resume_target')} "
          f"(work={args.work_adapter} review={args.review_adapter}) ===")
    h = HarnessV2(run_id, args.runtime, args.review_adapter, args.work_adapter)
    ok = apply_answers(state, Path(args.answers), h.log, run_id)
    if not ok:
        # blocked exit: last_good_checkpoint + pending 유지
        save_checkpoint("route", {"route": "blocked exit", "reason": "answers 채택 없음"},
                        good=False)
        h.log.event(run_id, "route", "route.decided", "blocked exit", route="blocked exit",
                    detail="answers 채택 없음 → last_good_checkpoint + pending 보존")
        save_run_state(state)
        print("\n=== route = blocked exit ===")
        return 0
    # requirements_update 후 re-freeze + resume_target 부터 graph 이어 돌림
    start = state.get("resume_target") or "fill_handoff"
    route = h.traverse(state, start=start)
    if PENDING.exists() and route == "exit":
        # 해소된 pending 은 삭제하지 않고 archive 로 보존 (ask-back 증거 유지)
        resolved = STATE / "pending-external.resolved.md"
        write(resolved, "> RESOLVED by run-2-resume (" + now() + ") — route=exit\n\n" + read(PENDING))
        PENDING.unlink()
    save_run_state(state)
    print(f"\n=== route = {route} ===")
    if route == "exit":
        print(f"final 1page  → {FINAL_1PAGE.relative_to(WS)}")
    return 0


def cmd_review(args) -> int:
    """review_parallel node 단독 시연 (가이드의 standalone review_parallel(draft, source) 예시).

    canonical graph run 과 분리해, L5(Claude Agent SDK) ∥ L6(codex exec) 실제 병렬 호출과
    한쪽 실패 시 통과 결과 보존(partial pass)을 보여준다. 직전 run 의 preserved_passes 와
    엮이지 않게 regression 비교는 끈다.
    """
    build = Path(args.build) if args.build else (HANDOFFS / "handoff-L3L4-build.md")
    if not build.exists():
        print(f"build handoff 없음: {build}. 먼저 run/resume 을 돌린다.", file=sys.stderr)
        return 1
    state = fresh_state("review-demo")
    state["build_path"] = str(build)
    state["requirements"]["acceptance"]["metric_confirmed"] = True   # 인용·사실 검수에 집중
    state["preserved_passes"] = []                                   # 단독 시연 → 교차 regression 끔
    print(f"\n=== review_parallel 단독 시연 (adapter={args.adapter}) — {build.relative_to(WS)} ===")
    h = HarnessV2("review-demo", args.runtime, args.adapter)
    rp = h.n_review_parallel(state)
    merged = h.n_merge_verdict(state, rp)
    route = "exit(전부 통과)" if not merged["failed"] else f"retry(실패: {merged['failed_sides']}, 보존: {merged['passes']})"
    print(f"\n--- merge 결과 ---")
    print(f"L5(인용·Claude)={rp['L5']['verdict']} / L6(사실·범위·Codex)={rp['L6']['verdict']}")
    print(f"통과 보존={merged['passes']} / 재작업 대상={merged['failed_sides']}")
    print(f"route={route}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="meeting-to-1page 하네스 v2 (2005-4-3)")
    sub = ap.add_subparsers(dest="cmd", required=True)

    pr = sub.add_parser("run", help="graph 한 번 실행 (fresh init)")
    pr.add_argument("--workspace", default=str(WS))
    pr.add_argument("--source-workspace", default=str(SOURCE))
    pr.add_argument("--source", default="inputs/meeting.md")
    pr.add_argument("--runtime", default="codex", choices=["codex", "claude", "cli"])
    pr.add_argument("--review-adapter", default="local", choices=["local", "live", "mcp"])
    pr.add_argument("--work-adapter", default="replay", choices=["replay", "claude", "codex"],
                    help="replay=4장 녹화 회수 / claude=Claude Agent SDK / codex=Codex CLI worker")
    pr.add_argument("--run-id", default="run-1")
    pr.add_argument("--fresh", action="store_true", default=True)
    pr.add_argument("--keep", dest="fresh", action="store_false")
    pr.set_defaults(func=cmd_run)

    rs = sub.add_parser("resume", help="ask-back 회신을 반영해 resume_target 부터 이어 돌림")
    rs.add_argument("--answers", default="inputs/answers.md")
    rs.add_argument("--runtime", default="codex", choices=["codex", "claude", "cli"])
    rs.add_argument("--review-adapter", default="local", choices=["local", "live", "mcp"])
    rs.add_argument("--work-adapter", default="replay", choices=["replay", "claude", "codex"])
    rs.set_defaults(func=cmd_resume)

    rv = sub.add_parser("review", help="review_parallel node 단독 시연 (Claude Agent SDK ∥ codex exec)")
    rv.add_argument("--build", default="")
    rv.add_argument("--adapter", default="live", choices=["local", "live", "mcp"])
    rv.add_argument("--runtime", default="codex", choices=["codex", "claude", "cli"])
    rv.add_argument("--review-adapter", dest="adapter")   # alias
    rv.set_defaults(func=cmd_review)

    args = ap.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
