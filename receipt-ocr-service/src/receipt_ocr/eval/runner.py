"""평가 러너 — 골든셋을 프로바이더에 통과시키고 리포트를 만든다.

**지연 수치의 전제**: ``workers=1`` (기본)일 때만 p50/p95 가 의미 있다. 동시 호출을 켜면 큐잉과
레이트리밋이 섞여 들어가 모델의 응답 시간이 아니라 하네스의 처리량을 재게 된다. 그래서 병렬은
"빨리 돌려보고 싶을 때"용이고, 리포트에 지연을 인용할 거라면 순차로 다시 돌린다.
"""

from __future__ import annotations

import pathlib
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal

from ..providers.base import Provider
from .scorer import EvalReport, GoldenCase, Prediction, score

#: 확장자 → MIME. 업로드는 대개 휴대폰 사진(JPEG)이다.
_MIME = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".webp": "image/webp"}


def _mime_for(path: pathlib.Path) -> str:
    return _MIME.get(path.suffix.lower(), "image/jpeg")


def _predict_one(provider: Provider, case: GoldenCase) -> Prediction:
    """케이스 1건. 이미지가 없으면 호출하지 않고 실패로 센다(조용히 빠지면 점수가 부풀려진다)."""
    if not case.image_path:
        return Prediction(case.case_id, None, error="이미지 경로 없음")
    path = pathlib.Path(case.image_path)
    if not path.exists():
        return Prediction(case.case_id, None, error=f"이미지 파일 없음: {path}")

    result = provider.extract(path.read_bytes(), _mime_for(path))
    return Prediction(
        case_id=case.case_id,
        extracted=result.extracted,
        latency_ms=result.latency_ms,
        cost_usd=result.cost_usd,
        error=result.error,
    )


def run_provider(provider: Provider, cases: list[GoldenCase], *, workers: int = 1,
                 progress: bool = False) -> list[Prediction]:
    """골든셋 전체를 돌린다. 개별 실패는 예외로 번지지 않고 ``UNAVAILABLE`` 로 집계된다."""
    if workers <= 1:
        predictions = []
        for index, case in enumerate(cases, start=1):
            predictions.append(_predict_one(provider, case))
            if progress:
                print(f"  [{index}/{len(cases)}] {case.case_id}", flush=True)
        return predictions

    with ThreadPoolExecutor(max_workers=workers) as pool:
        return list(pool.map(lambda c: _predict_one(provider, c), cases))


def evaluate(provider: Provider, cases: list[GoldenCase], review_threshold: Decimal, *,
             workers: int = 1, progress: bool = False) -> tuple[EvalReport, list[Prediction]]:
    predictions = run_provider(provider, cases, workers=workers, progress=progress)
    return score(cases, predictions, review_threshold), predictions


# --------------------------------------------------------------------------------------
# 리포트 출력
# --------------------------------------------------------------------------------------


def slice_reports(cases: list[GoldenCase], predictions: list[Prediction],
                  review_threshold: Decimal, key: str) -> dict[str, EvalReport]:
    """시나리오별·조건별로 잘라 다시 채점한다.

    전체 정확도 하나로는 "저조도에서만 무너진다" 를 볼 수 없다. 개선할 곳을 고르려면 이 단면이
    필요하다.
    """
    by_id = {p.case_id: p for p in predictions}
    groups: dict[str, list[GoldenCase]] = {}
    for case in cases:
        groups.setdefault(getattr(case, key) or "(없음)", []).append(case)
    return {
        name: score(group, [by_id[c.case_id] for c in group if c.case_id in by_id], review_threshold)
        for name, group in sorted(groups.items())
    }


def _pct(value: float) -> str:
    return f"{value * 100:5.1f}%"


def format_report(report: EvalReport, provider_name: str, *,
                  slices: dict[str, dict[str, EvalReport]] | None = None,
                  show_failures: int = 12) -> str:
    """사람이 읽는 리포트. **정확도 한 줄만 보지 말라**는 걸 배치로 강제한다."""
    from ..domain.matcher import Outcome

    lines = [
        "=" * 72,
        f"  영수증 OCR 평가 — {provider_name}",
        "=" * 72,
        f"  케이스            : {report.n} 건",
        f"  대사 판정 일치율  : {_pct(report.accuracy)}",
        "",
        "  [치명 오류] — 합격 판정의 실질 기준",
        f"    멀쩡한 영수증 오종결 (MATCHED→MISMATCHED) : {report.critical_false_mismatch} 건",
        f"    증빙없는 지출 통과  (MISMATCHED→MATCHED)  : {report.critical_false_match} 건",
        f"    리뷰 대상 조기 종결 (NEEDS_REVIEW→종결)   : {report.premature_close} 건",
        "",
        "  [운영 부하]",
        f"    리뷰 큐 유입률    : {_pct(report.review_rate)}",
        f"    추출 실패율(503)  : {_pct(report.unavailable_rate)}",
        "",
        "  [필드 판독] — 추출 성공분 기준",
        f"    총액 정확 일치    : {_pct(report.amount_exact_rate)}",
        f"    거래일 허용내     : {_pct(report.date_within_tolerance_rate)}",
        "",
        "  [신뢰도 교정]",
        f"    ECE               : {report.ece:.4f}  (0 에 가까울수록 신뢰도가 정직하다)",
        "",
        "  [비용]",
        f"    지연 p50 / p95    : {report.latency_p50_ms:.0f}ms / {report.latency_p95_ms:.0f}ms",
        f"    총 비용           : ${report.total_cost_usd}",
        f"    가중 오류비용     : {report.weighted_cost} (건당 {report.weighted_cost_per_case:.2f})",
        "",
        "  [혼동 행렬]  행=정답, 열=예측",
    ]

    order = [Outcome.MATCHED, Outcome.NEEDS_REVIEW, Outcome.MISMATCHED, Outcome.UNAVAILABLE]
    header = "".join(f"{o.value[:9]:>11s}" for o in order)
    lines.append(f"    {'':>14s}{header}")
    for truth in order:
        row = "".join(f"{report.confusion.get((truth, pred), 0):>11d}" for pred in order)
        if any(report.confusion.get((truth, pred), 0) for pred in order):
            lines.append(f"    {truth.value:>14s}{row}")

    for title, sub in (slices or {}).items():
        lines += ["", f"  [{title}별 단면]",
                  f"    {'':16s}{'건수':>6s}{'일치율':>9s}{'치명':>7s}{'리뷰':>9s}{'실패':>9s}"]
        for name, sub_report in sub.items():
            lines.append(
                f"    {name:16s}{sub_report.n:>6d}{_pct(sub_report.accuracy):>9s}"
                f"{sub_report.critical_total:>7d}{_pct(sub_report.review_rate):>9s}"
                f"{_pct(sub_report.unavailable_rate):>9s}"
            )

    if report.failures and show_failures:
        lines += ["", f"  [실패 상세] 상위 {min(show_failures, len(report.failures))}건"]
        lines += [f"    - {line}" for line in report.failures[:show_failures]]
        if len(report.failures) > show_failures:
            lines.append(f"    ... 외 {len(report.failures) - show_failures}건")

    lines.append("=" * 72)
    return "\n".join(lines)
