#!/usr/bin/env python3
"""Small deterministic review helper for 2004-4-3 handoff markdown."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


REQUIRED_SECTIONS = [
    "## From task",
    "## Result",
    "- 목적:",
    "- 입력:",
    "- 범위",
    "- 금지:",
    "- 완료조건:",
    "- 결과 요약:",
    "- 판정:",
    "- 남은 질문:",
    "- 다음 세션의 첫 행동:",
]

VERDICTS = ("통과", "재작업", "재질문", "종료", "pass", "rework", "ask-back", "exit")
QUALIFIERS = ("확인 필요", "보류", "미확인", "출처", "남은 질문", "단정 금지", "단정하지", "추정", "ask-back", "재질문")

LEAF_RULES = {
    "L1": {
        "must_any": [
            ["결정권자", "담당자", "책임자"],
            ["일정"],
            ["수치", "60%"],
            ["환불"],
        ],
        "evidence": ["회의록", "meeting.md", "박PM", "한CS", "김디자이너", "정BE"],
    },
    "L5": {
        "must_any": [["인용"], ["원문"], ["줄번호", "meeting.md"]],
        "evidence": ["meeting.md", "\"", "원문"],
    },
    "L6": {
        "must_any": [["외부 사실"], ["미확인 수치", "60%"], ["범위 밖"], ["미결정"]],
        "evidence": ["meeting.md", "근거", "줄"],
    },
}


def contains_any(text: str, needles: list[str]) -> bool:
    return any(needle in text for needle in needles)


def extract_verdict(text: str) -> str | None:
    match = re.search(r"- 판정:\s*([^\n]+)", text)
    if not match:
        return None
    raw = match.group(1).strip()
    for verdict in VERDICTS:
        if verdict in raw:
            return verdict
    return None


def review(text: str, leaf: str) -> tuple[str, list[str], str]:
    failures: list[str] = []

    for marker in REQUIRED_SECTIONS:
        if marker not in text:
            failures.append(f"format missing: {marker}")

    verdict = extract_verdict(text)
    if verdict is None:
        failures.append("format missing: known verdict in `- 판정:`")

    rules = LEAF_RULES.get(leaf, {})
    for group in rules.get("must_any", []):
        if not contains_any(text, group):
            failures.append(f"evidence missing leaf keyword group: {' / '.join(group)}")

    if rules and not contains_any(text, rules.get("evidence", [])):
        failures.append("evidence missing: no recognizable meeting source")

    if "60%" in text and "출처 미확인" not in text and "추정" not in text:
        failures.append("FN correction: `60%` appears without uncertainty marker")

    if "결제 모듈" in text and "범위 밖" not in text and "금지" not in text:
        failures.append("FN correction: payment modal scope may be expanded")

    if failures:
        route = "rework"
        if "운영팀 회신 대기" in text or "외부 결정" in text:
            route = "ask-back"
        return route, failures, "revise only the failed fields and preserve grounded evidence"

    normalized = {
        "통과": "pass",
        "재작업": "rework",
        "재질문": "ask-back",
        "종료": "exit",
    }.get(verdict or "", verdict or "rework")

    return normalized, [], "move to next leaf or merge gate"


def meeting_path_for(handoff: Path) -> Path | None:
    current = handoff.resolve().parent
    for _ in range(6):
        candidate = current / "inputs" / "meeting.md"
        if candidate.is_file():
            return candidate
        current = current.parent
    return None


def deterministic_post_tool_review(handoff: Path) -> list[str]:
    text = handoff.read_text(encoding="utf-8")
    violations: list[str] = []

    if "## From task" not in text:
        violations.append("format: `## From task` section is missing")
    if "## Result" not in text:
        violations.append("format: `## Result` section is missing")

    verdict_line = re.search(r"(?:판정|verdict)\s*[:：]\s*(.+)", text, flags=re.IGNORECASE)
    if not verdict_line:
        violations.append("format: verdict line is missing")
    else:
        raw = verdict_line.group(1)
        if not any(v.lower() in raw.lower() for v in VERDICTS):
            violations.append(f"format: unknown verdict `{raw.strip()}`")

    if not ("다음" in text and "행동" in text):
        violations.append("format: next action is missing")

    cites = sorted({int(n) for n in re.findall(r"meeting\.md:(\d+)", text)})
    if cites:
        meeting = meeting_path_for(handoff)
        if meeting is None:
            violations.append("evidence: `inputs/meeting.md` not found")
        else:
            line_count = len(meeting.read_text(encoding="utf-8").splitlines())
            bad = [n for n in cites if n < 1 or n > line_count]
            if bad:
                violations.append(f"evidence: missing meeting.md line(s) {bad}; file has {line_count} lines")

    for line in text.splitlines():
        if re.search(r"60\s*%", line) and not any(q in line for q in QUALIFIERS):
            violations.append(f"FN(value): `60%` appears without uncertainty marker -> {line.strip()}")
            break

    return violations


def run_post_tool_mode() -> int:
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return 0

    tool_input = payload.get("tool_input", {}) or {}
    file_path = tool_input.get("file_path", "") or ""
    if not file_path:
        # apply_patch payloads may not expose a single file_path. Keep this hook
        # conservative and let explicit CLI review handle multi-file patches.
        return 0

    target = Path(file_path)
    norm = str(target).replace("\\", "/")
    if "/handoffs/" not in norm or target.suffix != ".md" or not target.is_file():
        return 0

    violations = deterministic_post_tool_review(target)
    if not violations:
        return 0

    print(f"[review gate] {target.name} deterministic precheck failed:", file=sys.stderr)
    for violation in violations:
        print(f"  - {violation}", file=sys.stderr)
    print("Fix only the listed fields in the same handoff file. Do not rewrite unrelated content.", file=sys.stderr)
    return 2


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("handoff", type=Path, nargs="?")
    parser.add_argument("--leaf", choices=sorted(LEAF_RULES))
    args = parser.parse_args()

    if args.handoff is None:
        return run_post_tool_mode()
    if args.leaf is None:
        parser.error("--leaf is required when a handoff path is provided")

    text = args.handoff.read_text(encoding="utf-8")
    route, failures, next_action = review(text, args.leaf)

    print(f"route: {route}")
    if failures:
        print("failures:")
        for failure in failures:
            print(f"- {failure}")
    else:
        print("failures: none")
    print(f"next_action: {next_action}")
    return 0 if route == "pass" else 1


if __name__ == "__main__":
    raise SystemExit(main())
