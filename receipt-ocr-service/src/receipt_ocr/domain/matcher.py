"""매입 ↔ 영수증 대사 규칙 — card-service ``ExpenseReceiptMatcher`` 의 파이썬 대응물.

**왜 규칙을 두 벌 두는가**: 채점을 필드 정확도(총액 일치율)로 하면 도메인적으로 의미 없는 숫자가
나온다. 운영에서 실제로 갈리는 건 최종 대사 판정(MATCHED / NEEDS_REVIEW / MISMATCHED)이고,
그 판정은 이 규칙이 만든다. 그래서 평가 하네스는 모델 출력을 이 규칙에 통과시킨 **판정끼리**
대조한다.

두 벌 구현의 드리프트는 ``tests/test_matcher.py`` 가 Java 주석에 적힌 정책을 그대로 케이스로
박아 막는다. 규칙이 바뀌면 양쪽이 같이 바뀌어야 한다.
"""

from __future__ import annotations

import datetime as _dt
from dataclasses import dataclass
from decimal import Decimal
from enum import Enum
from zoneinfo import ZoneInfo

from .extracted import ExtractedReceipt

#: 국내 VAN 매입은 KST 로 전표가 찍힌다.
KST = ZoneInfo("Asia/Seoul")

#: 매입일과 영수증 거래일 사이 허용 오차 — VAN 매입 시점과 전표 시점의 하루 차를 흡수한다.
DATE_TOLERANCE_DAYS = 1


class Outcome(str, Enum):
    """대사 판정 — card-service ``ExpenseReceiptStatus`` 의 종결/리뷰 상태."""

    MATCHED = "MATCHED"
    NEEDS_REVIEW = "NEEDS_REVIEW"
    MISMATCHED = "MISMATCHED"
    #: 추출 자체가 실패해 영수증 행이 만들어지지 않은 경우(운영에서는 503).
    UNAVAILABLE = "UNAVAILABLE"


@dataclass(frozen=True)
class MatchDecision:
    outcome: Outcome
    note: str = ""


def decide(
    extracted: ExtractedReceipt,
    captured_amount: Decimal,
    captured_at: _dt.datetime,
    review_threshold: Decimal,
) -> MatchDecision:
    """영수증 추출값과 매입을 대조해 도달할 상태를 정한다.

    **판정 순서가 곧 정책이다** — 신뢰도 → 총액 → 거래일. 순서를 바꾸면 믿을 수 없는 값으로
    불일치를 선고하게 되고, 멀쩡한 영수증이 되돌리기 어려운 종결로 떨어진다.

    :param captured_amount: 매입 금액 (``CardCapture.capturedAmount``)
    :param captured_at: 매입 시각 — KST 로 환산해 거래일을 얻는다
    :param review_threshold: 신뢰도 리뷰 임계 — **미만**이면 NEEDS_REVIEW
    """
    if (
        extracted is None
        or captured_amount is None
        or captured_at is None
        or review_threshold is None
    ):
        raise ValueError("대사 입력은 전부 필수입니다")

    if extracted.confidence < review_threshold:
        return MatchDecision(
            Outcome.NEEDS_REVIEW,
            f"판독 신뢰도 미달: {extracted.confidence} < {review_threshold}",
        )

    if extracted.total_amount != captured_amount:
        return MatchDecision(
            Outcome.MISMATCHED,
            f"총액 불일치: 영수증 {extracted.total_amount} vs 매입 {captured_amount}",
        )

    if extracted.transaction_date is None:
        return MatchDecision(Outcome.NEEDS_REVIEW, "거래일 판독 불가 — 육안 대조 필요")

    captured_date = captured_at.astimezone(KST).date()
    day_diff = abs((extracted.transaction_date - captured_date).days)
    if day_diff > DATE_TOLERANCE_DAYS:
        return MatchDecision(
            Outcome.MISMATCHED,
            f"거래일 불일치: 영수증 {extracted.transaction_date} vs 매입일(KST) {captured_date}",
        )

    return MatchDecision(Outcome.MATCHED, "매입과 일치")
