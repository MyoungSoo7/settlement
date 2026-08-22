"""임계 탐색 — 리뷰 임계는 모델 상수가 아니라 **정책 손잡이**다.

운영 기본값 0.80 은 "신뢰도가 확률일 때" 말이 되는 숫자다. 원점수는 확률이 아니었으므로 그
숫자를 그대로 쓰는 것 자체가 근거 없는 선택이었다(Phase 1 에서 리뷰 큐 62.9% 가 그 결과다).

교정 후에는 신뢰도가 실제 확률이므로, **비대칭 비용을 최소화하는 임계를 계산할 수 있다.**
오종결 25 vs 리뷰 1 이라는 비용 구조에서는 임계를 낮출수록(=더 많이 종결) 리뷰 인건비가 줄지만
치명 오류가 는다. 그 교환의 최저점이 운영점이다.

.. danger::
   임계는 **학습셋에서** 고른다. 홀드아웃에서 고르면 그 홀드아웃 점수는 더 이상 일반화 추정치가
   아니다 — 임계 하나도 그 셋에 맞춘 파라미터이기 때문이다.
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal

from ..eval.scorer import GoldenCase, Prediction, score


@dataclass(frozen=True)
class SweepPoint:
    threshold: Decimal
    weighted_cost: int
    accuracy: float
    review_rate: float
    critical_total: int

    def __str__(self) -> str:
        return (f"임계 {self.threshold}  비용 {self.weighted_cost:4d}  "
                f"일치율 {self.accuracy * 100:5.1f}%  리뷰 {self.review_rate * 100:5.1f}%  "
                f"치명 {self.critical_total}")


def sweep(cases: list[GoldenCase], predictions: list[Prediction],
          thresholds: list[Decimal]) -> list[SweepPoint]:
    """임계별로 다시 채점한다. 예측은 그대로 두고 **정책만** 바꿔 본다."""
    points = []
    for threshold in thresholds:
        report = score(cases, predictions, threshold)
        points.append(
            SweepPoint(
                threshold=threshold,
                weighted_cost=report.weighted_cost,
                accuracy=report.accuracy,
                review_rate=report.review_rate,
                critical_total=report.critical_total,
            )
        )
    return points


def default_grid(step: str = "0.05") -> list[Decimal]:
    """0.05 ~ 0.95 격자. 0 과 1 은 제외한다 — 전부 종결/전부 리뷰는 정책이 아니다."""
    increment = Decimal(step)
    grid, value = [], increment
    while value < Decimal("1"):
        grid.append(value)
        value += increment
    return grid


def best(points: list[SweepPoint]) -> SweepPoint:
    """가중 비용 최소점. 동률이면 **치명 오류가 적은 쪽**, 그다음 리뷰가 적은 쪽.

    동률 처리에 순서를 두는 이유: 비용이 같아도 치명 오류 1건과 리뷰 25건은 성격이 다르다.
    되돌릴 수 없는 오종결을 더 싫어하는 쪽으로 기울인다.
    """
    if not points:
        raise ValueError("탐색 결과가 비었습니다")
    return min(points, key=lambda p: (p.weighted_cost, p.critical_total, p.review_rate))
