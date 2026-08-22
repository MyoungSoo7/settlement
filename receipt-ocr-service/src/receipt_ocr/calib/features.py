"""판독 신뢰도를 예측할 특징 — "이 판독이 맞을 확률"의 재료.

**왜 OCR 점수를 그대로 쓰면 안 되는가**: RapidOCR 이 주는 점수는 "이 글자를 이렇게 읽었다"는
확신이지 "이 값이 총액이다"라는 확신이 아니다. 스케일도 운영 임계(0.80)와 맞지 않는다 —
Phase 1 실측에서 ECE 0.457, 리뷰 큐 62.9% 가 그 결과였다.

그래서 점수 **하나가 아니라 판독 과정이 남긴 흔적 전부**를 특징으로 쓴다. 어떤 근거로 골랐는지
(구조 검증/글자 크기), 후보가 몇이었는지, 페이지 전체가 얼마나 잘 읽혔는지 같은 것들이다.

축은 둘이고 **서로 다른 특징을 쓴다** — 총액과 거래일은 영수증의 다른 영역에서 읽히고, 뭉개지는
이유도 다르다. 한 모델로 합치면 이 프로젝트가 없애려던 결함(한 축이 다른 축을 덮는 것)이
학습 단계에서 되살아난다.
"""

from __future__ import annotations

import statistics
from dataclasses import dataclass

from ..providers.parsing import (
    _AMOUNT,
    _TIME,
    OcrLine,
    ParsedReceipt,
    find_date,
)

#: 총액 축 특징 이름 — 학습·서빙이 같은 순서를 써야 한다(계수는 순서로 저장된다).
AMOUNT_FEATURES = (
    "amount_score",        # 채택한 총액 토큰의 OCR 점수
    "is_structural",       # 공급가액+부가세=합계 가 섰는가 (가장 강한 근거)
    "amount_candidates",   # 금액 후보 수 — 많을수록 헷갈릴 여지가 크다
    "height_ratio",        # 채택 토큰 높이 / 페이지 중앙값 — 합계 줄은 크게 찍힌다
    "page_score",          # 페이지 전체 OCR 점수 평균 (판독 품질 대리값)
    "line_count",          # 검출된 줄 수 — 적으면 영수증을 제대로 못 잡은 것이다
)

#: 거래일 축 특징 이름.
DATE_FEATURES = (
    "date_score",          # 채택한 거래일 줄의 OCR 점수
    "has_time",            # 같은 줄에 시각이 있는가 — 진짜 거래일시의 표지
    "date_candidates",     # 날짜 모양 후보 수 — 여럿이면 승인번호를 오인했을 수 있다
    "page_score",
    "line_count",
)


@dataclass(frozen=True)
class Sample:
    """케이스 1건에서 뽑은 두 축의 특징과 정답 라벨."""

    case_id: str
    amount: dict[str, float]
    date: dict[str, float] | None      # 거래일을 못 읽었으면 None (학습 대상이 아니다)
    amount_correct: bool
    date_correct: bool | None
    scenario: str = ""
    condition: str = ""


def _page_stats(lines: list[OcrLine]) -> tuple[float, float, float]:
    """(점수 평균, 높이 중앙값, 줄 수)."""
    if not lines:
        return 0.0, 1.0, 0.0
    scores = [ln.confidence for ln in lines]
    heights = [ln.height for ln in lines if ln.height > 0] or [1.0]
    return sum(scores) / len(scores), statistics.median(heights), float(len(lines))


def _amount_candidate_count(lines: list[OcrLine]) -> int:
    total = 0
    for line in lines:
        for match in _AMOUNT.finditer(line.text):
            if match.start() > 0 and line.text[match.start() - 1] == "-":
                continue
            total += 1
    return total


def _date_candidate_count(lines: list[OcrLine]) -> int:
    # find_date 는 최선 하나만 주므로, 후보 수는 줄 단위로 다시 센다.
    return sum(1 for line in lines if find_date([line]) is not None)


def amount_features(parsed: ParsedReceipt, lines: list[OcrLine]) -> dict[str, float]:
    page_score, median_height, line_count = _page_stats(lines)
    chosen_height = next(
        (ln.height for ln in lines if parsed.amount_source and parsed.amount_source in ln.text),
        median_height,
    )
    return {
        "amount_score": float(parsed.amount_confidence),
        "is_structural": 1.0 if parsed.amount_method == "structural" else 0.0,
        "amount_candidates": float(_amount_candidate_count(lines)),
        "height_ratio": float(chosen_height / median_height) if median_height else 1.0,
        "page_score": page_score,
        "line_count": line_count,
    }


def date_features(parsed: ParsedReceipt, lines: list[OcrLine]) -> dict[str, float] | None:
    if parsed.date_confidence is None:
        return None
    page_score, _, line_count = _page_stats(lines)
    source = parsed.date_source or ""
    has_time = any(
        _TIME.search(ln.text) for ln in lines if source and source in ln.text
    )
    return {
        "date_score": float(parsed.date_confidence),
        "has_time": 1.0 if has_time else 0.0,
        "date_candidates": float(_date_candidate_count(lines)),
        "page_score": page_score,
        "line_count": line_count,
    }


def vector(features: dict[str, float], names: tuple[str, ...]) -> list[float]:
    """이름 순서대로 편다 — 학습과 서빙이 어긋나지 않게 하는 유일한 장치다."""
    return [float(features[name]) for name in names]
