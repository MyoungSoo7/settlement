#!/usr/bin/env python3
"""PostToolUse review gate — handoffs/ 산출물의 결정론 사전 검수.

트리거: Write 또는 Edit 직후 (settings.json matcher: Write|Edit).
대상:   파일 경로에 `/handoffs/` 가 들어간 .md 만. 그 외 파일은 조용히 통과.

이 hook이 보는 것 (결정론으로 기계 판정 가능한 슬라이스만):
  ① format   — handoff 필수 섹션(From task / Result)과 판정 줄이 있는가,
                판정 값이 정해진 4종(통과·재작업·재질문·종료)에 드는가, 다음 행동이 있는가.
  ② evidence — `meeting.md:NN` 인용의 줄번호 NN이 inputs/meeting.md 안에 실재하는가.
  ③ FN(값)   — `60%` 같은 미확인 수치가 보정 단서 없이 본문 한 줄에 단정됐는가.

이 hook이 보지 않는 것 (LLM-judge = review sub agent 몫):
  - 인용한 줄이 그 주장을 실제로 뒷받침하는가 (의미 대조).
  - 성공 기준이 목표 문장인지 측정 지표인지 (모호함 판단 -> ask-back).
  - 표현 차이 vs 값 누락의 경계 (false-negative 보정의 의미 판단).
즉 이 hook은 review gate의 '값·구조' 슬라이스만 결정론으로 잡고, '의미' 슬라이스는
review sub agent가 판정한다. 같은 입력에 같은 verdict를 끝까지 보장하는 재현 gate는
5장 Ouroboros로 넘긴다.

규약: 위반 시 stderr로 위반 항목만 출력하고 exit 2 (Claude가 같은 파일을 위반 부분만
      보정해 다시 쓴다). 위반 없으면 조용히 exit 0.
"""
import json
import os
import re
import sys

ALLOWED_VERDICTS = ["통과", "pass", "재작업", "rework", "재질문", "ask-back", "종료", "exit"]
QUALIFIERS = ("확인 필요", "보류", "미확인", "출처", "남은 질문", "단정 금지",
              "단정하지", "추정", "ask-back", "재질문")


def find_meeting(file_path):
    """inputs/meeting.md 위치 탐색: CLAUDE_PROJECT_DIR 우선, 없으면 상향 탐색."""
    cands = []
    root = os.environ.get("CLAUDE_PROJECT_DIR")
    if root:
        cands.append(os.path.join(root, "inputs", "meeting.md"))
    d = os.path.dirname(os.path.abspath(file_path))
    for _ in range(5):
        cands.append(os.path.join(d, "inputs", "meeting.md"))
        d = os.path.dirname(d)
    for c in cands:
        if os.path.isfile(c):
            return c
    return None


def verdict_ok(line):
    low = line.lower()
    for a in ALLOWED_VERDICTS:
        if a.isascii():
            if a in low:
                return True
        elif a in line:
            return True
    return False


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        sys.exit(0)  # 입력 파싱 실패는 사후 검수를 막지 않는다.

    tool_input = payload.get("tool_input", {}) or {}
    file_path = tool_input.get("file_path", "") or ""
    norm = file_path.replace("\\", "/")

    # 대상 필터: handoffs/ 산출물 .md 만.
    if "/handoffs/" not in norm or not file_path.endswith(".md"):
        sys.exit(0)
    if not os.path.isfile(file_path):
        sys.exit(0)

    with open(file_path, encoding="utf-8") as f:
        text = f.read()

    violations = []

    # ① format — 필수 섹션.
    if "## From task" not in text:
        violations.append("format: `## From task` 섹션이 없다.")
    if "## Result" not in text:
        violations.append("format: `## Result` 섹션이 없다.")

    # ① format — 판정 값.
    m = re.search(r"(?:판정|verdict)\s*[:：]\s*(.+)", text)
    if not m:
        violations.append("format: `판정:` 줄이 없다 (통과/재작업/재질문/종료 중 하나).")
    elif not verdict_ok(m.group(1)):
        violations.append(
            "format: 판정 값이 4종(통과·재작업·재질문·종료/pass·rework·ask-back·exit)에 "
            "없다 -> '%s'" % m.group(1).strip())

    # ① format — 다음 행동.
    if not ("다음" in text and "행동" in text):
        violations.append("format: `다음 세션의 첫 행동` 줄이 없다.")

    # ② evidence — meeting.md:NN 줄 실재.
    cites = set(int(n) for n in re.findall(r"meeting\.md:(\d+)", text))
    if cites:
        mp = find_meeting(file_path)
        if mp is None:
            violations.append("evidence: meeting.md를 찾지 못해 인용 줄을 대조할 수 없다.")
        else:
            with open(mp, encoding="utf-8") as f:
                n_lines = sum(1 for _ in f)
            bad = sorted(n for n in cites if n < 1 or n > n_lines)
            if bad:
                violations.append(
                    "evidence: meeting.md에 없는 줄 인용 %s (파일은 %d줄)." % (bad, n_lines))

    # ③ FN(값) — 60% 무단정.
    for ln in text.splitlines():
        if re.search(r"60\s*%", ln) and not any(q in ln for q in QUALIFIERS):
            violations.append(
                f"FN(값): `60%`가 보정 단서 없이 본문 한 줄에 단정됨 -> '{ln.strip()}' "
                "(출처 확정 전엔 확인 필요 항목으로만).")
            break

    if not violations:
        sys.exit(0)

    sys.stderr.write("[review gate] %s 결정론 사전 검수 위반:\n"
                     % os.path.basename(file_path))
    for v in violations:
        sys.stderr.write("  - " + v + "\n")
    sys.stderr.write(
        "위 항목만 정확히 보정해 같은 파일을 다시 써라. 전체 재작성 금지. "
        "의미 판정(인용이 주장을 뒷받침하나 · 성공 기준이 목표인지 지표인지)은 "
        "review sub agent가 따로 본다.\n")
    sys.exit(2)


if __name__ == "__main__":
    main()
