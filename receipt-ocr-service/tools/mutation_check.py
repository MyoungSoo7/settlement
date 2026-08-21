"""뮤테이션 점검 — 정책을 한 군데씩 망가뜨렸을 때 테스트가 잡아내는지 확인한다.

**왜 필요한가**: 통과하는 테스트는 그 자체로는 검증력의 증거가 아니다. 실패를 목격하지 못한 채
구현이 먼저 나온 테스트는 특히 그렇다(``tdd-discipline``). 여기 나열된 뮤테이션은 전부 이 도메인의
**실제 정책 문장 하나씩**을 뒤집은 것이라, 하나라도 MISSED 가 나오면 그 정책은 테스트가 지키지
않고 있다는 뜻이다.

각 뮤테이션은 적용 → pytest → 원복 순으로 처리한다. 원복은 ``finally`` 에서 무조건 수행한다.

사용::

    .venv/Scripts/python.exe tools/mutation_check.py
"""

from __future__ import annotations

import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MATCHER = ROOT / "src" / "receipt_ocr" / "domain" / "matcher.py"
SCORER = ROOT / "src" / "receipt_ocr" / "eval" / "scorer.py"
PARSING = ROOT / "src" / "receipt_ocr" / "providers" / "parsing.py"


def _python() -> str:
    """저장소 venv 가 있으면 그걸 쓰고, 없으면 현재 인터프리터."""
    for candidate in (ROOT / ".venv" / "Scripts" / "python.exe", ROOT / ".venv" / "bin" / "python"):
        if candidate.exists():
            return str(candidate)
    return sys.executable


#: (설명, 대상파일, 원본조각, 망가뜨린조각) — 전부 CAUGHT 여야 한다.
MUTATIONS: list[tuple[str, pathlib.Path, str, str]] = [
    (
        "매처: 신뢰도 게이트 무력화 (판정 순서 정책 파괴)",
        MATCHER,
        "    if extracted.confidence < review_threshold:",
        "    if False and extracted.confidence < review_threshold:",
    ),
    (
        "매처: 거래일 허용 오차를 1일 -> 2일로 확대",
        MATCHER,
        "DATE_TOLERANCE_DAYS = 1",
        "DATE_TOLERANCE_DAYS = 2",
    ),
    (
        "매처: 매입일을 KST 대신 UTC 로 환산",
        MATCHER,
        "captured_date = captured_at.astimezone(KST).date()",
        "captured_date = captured_at.date()",
    ),
    (
        "매처: 총액에 1원 허용 오차 부여",
        MATCHER,
        "    if extracted.total_amount != captured_amount:",
        "    if abs(extracted.total_amount - captured_amount) > 1:",
    ),
    (
        "채점기: 예측 누락 케이스를 조용히 건너뛰기 (점수 부풀리기)",
        SCORER,
        "        pred = by_id.get(case.case_id)\n",
        "        pred = by_id.get(case.case_id)\n        if pred is None:\n            continue\n",
    ),
    (
        "채점기: 치명 오류 두 칸을 구분 없이 같은 셀로 집계",
        SCORER,
        "critical_false_match=confusion[(Outcome.MISMATCHED, Outcome.MATCHED)],",
        "critical_false_match=confusion[(Outcome.MATCHED, Outcome.MISMATCHED)],",
    ),
    (
        "채점기: 리뷰로 보낸 오답에도 치명 비용 부과 (비대칭 정책 파괴)",
        SCORER,
        "    if pred is Outcome.NEEDS_REVIEW:\n        return COST_REVIEW",
        "    if pred is Outcome.NEEDS_REVIEW:\n        return COST_FALSE_MATCH",
    ),
    (
        "채점기: ECE 를 구간 크기 가중 없이 단순 평균",
        SCORER,
        "        ece += weight * abs(accuracy - avg_confidence)",
        "        ece += abs(accuracy - avg_confidence) / len(buckets)",
    ),
    (
        "파서: 필드별 신뢰도를 최솟값 대신 최댓값으로 합침 (baseline 실패 재현)",
        PARSING,
        "    value = amount if date is None else min(amount, date)",
        "    value = amount if date is None else max(amount, date)",
    ),
    (
        "파서: 구조 검증(공급가액+부가세=합계) 무력화",
        PARSING,
        "    structural = _structural_total(candidates)",
        "    structural = None",
    ),
    (
        "파서: 구조 검증 실패 시의 벌점 제거",
        PARSING,
        "LARGEST_PENALTY = 0.25",
        "LARGEST_PENALTY = 0.0",
    ),
    (
        "파서: 할인 음수줄을 총액 후보로 허용",
        PARSING,
        '            if match.start() > 0 and line.text[match.start() - 1] == "-":',
        "            if False:",
    ),
    (
        "파서: 말이 안 되는 날짜를 그대로 통과",
        PARSING,
        "    try:\n        return _dt.date(year, month, day)\n    except ValueError:\n        return None",
        "    try:\n        return _dt.date(year, month, 1)\n    except ValueError:\n        return None",
    ),
    (
        "채점기: 추출 실패를 정답으로 취급",
        SCORER,
        "            predicted = Outcome.UNAVAILABLE",
        "            predicted = truth",
    ),
]


def main() -> int:
    python = _python()
    caught = 0
    for label, path, old, new in MUTATIONS:
        original = path.read_text(encoding="utf-8")
        if old not in original:
            print(f"[SKIP  ] {label}\n         -> 앵커 문자열을 찾지 못했습니다(코드가 바뀌었나?)")
            continue
        path.write_text(original.replace(old, new, 1), encoding="utf-8")
        try:
            result = subprocess.run(
                [python, "-m", "pytest", "tests/", "-q", "--no-header"],
                cwd=ROOT, capture_output=True, text=True,
            )
        finally:
            path.write_text(original, encoding="utf-8")

        if result.returncode != 0:
            caught += 1
            first = next(
                (ln for ln in result.stdout.splitlines() if ln.startswith("FAILED")), ""
            )
            print(f"[CAUGHT] {label}\n         -> {first.strip()}")
        else:
            print(f"[MISSED] {label}\n         -> 테스트가 이 정책을 지키지 않고 있습니다")

    total = len(MUTATIONS)
    print(f"\n{caught}/{total} 뮤테이션 검출")
    return 0 if caught == total else 1


if __name__ == "__main__":
    raise SystemExit(main())
