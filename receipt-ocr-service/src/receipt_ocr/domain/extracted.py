"""영수증 추출 결과 VO — card-service ``ExtractedReceipt`` 레코드의 파이썬 대응물.

평가 하네스는 모델 출력을 이 타입으로 정규화해서 채점한다. Java 쪽 VO 가 생성자에서 거부하는
값(총액 0/음수, 신뢰도 범위 밖)은 여기서도 똑같이 거부해야 한다 — 여기서 통과한 추출이
운영에서는 ``IllegalArgumentException`` 으로 떨어지면 평가 숫자가 거짓말이 된다.

금액은 ``float`` 이 아니라 :class:`decimal.Decimal` 이다(저장소 가드레일: 금액에 부동소수 금지).
"""

from __future__ import annotations

import datetime as _dt
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation

_ONE = Decimal("1")
_ZERO = Decimal("0")


def _normalize_text(value: str | None) -> str | None:
    """공백만 있는 상호명은 판독 실패(None)와 같게 취급한다 — Java VO 의 normalize 대응."""
    if value is None:
        return None
    trimmed = value.strip()
    return trimmed or None


def to_decimal(value: object) -> Decimal | None:
    """모델이 뱉은 임의 표현을 Decimal 로 정규화한다. 판독 불가는 None.

    문자열 금액은 ``"12,300원"`` 처럼 구분기호·단위가 섞여 오는 게 정상이라 숫자만 남긴다.
    ``float`` 입력은 이진 부동소수 오차를 그대로 들고 오므로 문자열 경유로 변환한다.
    """
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, Decimal):
        return value
    if isinstance(value, int):
        return Decimal(value)
    if isinstance(value, float):
        return Decimal(str(value))
    if isinstance(value, str):
        cleaned = "".join(ch for ch in value if ch.isdigit() or ch in ".-")
        if not cleaned or cleaned in {"-", ".", "-."}:
            return None
        try:
            return Decimal(cleaned)
        except InvalidOperation:
            return None
    return None


def to_date(value: object) -> _dt.date | None:
    """``YYYY-MM-DD`` 문자열/date 를 date 로. 판독 불가·형식 파손은 None(지어내지 않는다)."""
    if isinstance(value, _dt.datetime):
        return value.date()
    if isinstance(value, _dt.date):
        return value
    if isinstance(value, str):
        trimmed = value.strip()
        if not trimmed:
            return None
        try:
            return _dt.date.fromisoformat(trimmed)
        except ValueError:
            return None
    return None


@dataclass(frozen=True)
class ExtractedReceipt:
    """OCR 추출 결과 (불변).

    :param merchant_name: 상호명 — 판독 실패는 None. **대사 판정에 쓰지 않는다**(참고 정보).
    :param transaction_date: 거래일 — 판독 실패는 None.
    :param total_amount: 총액 — 필수·양수. 대사의 근거라 지어낼 수 없다.
    :param confidence: 판독 신뢰도 0~1 — 필수. 임계 미만이면 값과 무관하게 리뷰 큐로 간다.
    """

    merchant_name: str | None
    transaction_date: _dt.date | None
    total_amount: Decimal
    confidence: Decimal

    def __post_init__(self) -> None:
        if self.total_amount is None or self.total_amount <= _ZERO:
            raise ValueError(f"영수증 총액은 양수여야 합니다: {self.total_amount}")
        if self.confidence is None or self.confidence < _ZERO or self.confidence > _ONE:
            raise ValueError(f"판독 신뢰도는 0~1 이어야 합니다: {self.confidence}")
        object.__setattr__(self, "merchant_name", _normalize_text(self.merchant_name))
