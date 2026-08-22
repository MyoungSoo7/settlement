"""추출 VO 정규화·검증 테스트.

이 VO 는 card-service 의 ``ExtractedReceipt`` 레코드와 같은 값을 거부해야 한다. 여기서 통과한
추출이 운영에서 ``IllegalArgumentException`` 으로 떨어지면 평가 수치가 통째로 거짓말이 된다 —
그래서 "무엇을 받아들이지 않는가" 를 값 단위로 못박는다.
"""

from __future__ import annotations

import datetime as _dt
from decimal import Decimal

import pytest

from receipt_ocr.domain.extracted import ExtractedReceipt, to_date, to_decimal

DATE = _dt.date(2026, 3, 4)


class TestToDecimal:
    def test_판독불가는_None(self):
        assert to_decimal(None) is None

    def test_bool_은_금액이_아니다(self):
        # 파이썬에서 bool 은 int 의 서브클래스라, 막지 않으면 True 가 1원이 된다.
        assert to_decimal(True) is None
        assert to_decimal(False) is None

    def test_Decimal_은_그대로(self):
        value = Decimal("12300")
        assert to_decimal(value) is value

    def test_int_는_그대로_환산(self):
        assert to_decimal(12300) == Decimal("12300")

    def test_float_은_문자열_경유로_이진오차를_들이지_않는다(self):
        # Decimal(0.1) 은 0.1000000000000000055511... 이 된다. 문자열 경유가 정답.
        assert to_decimal(0.1) == Decimal("0.1")

    @pytest.mark.parametrize(
        "raw,expected",
        [
            ("12,300원", Decimal("12300")),
            ("₩ 12 300", Decimal("12300")),
            ("합계: 58,273", Decimal("58273")),
            ("-1200", Decimal("-1200")),
            ("1234.50", Decimal("1234.50")),
        ],
    )
    def test_구분기호와_단위가_섞여_와도_숫자만_남긴다(self, raw, expected):
        assert to_decimal(raw) == expected

    @pytest.mark.parametrize("raw", ["", "   ", "원", "-", ".", "-.", "삼만원"])
    def test_숫자가_없으면_지어내지_않는다(self, raw):
        assert to_decimal(raw) is None

    def test_숫자로_복원할_수_없는_문자열은_None(self):
        # 점이 여러 개면 Decimal 이 InvalidOperation 을 던진다 — 예외가 밖으로 새면 안 된다.
        assert to_decimal("1.2.3") is None

    def test_알_수_없는_타입은_None(self):
        assert to_decimal(["12300"]) is None
        assert to_decimal({"amount": 1}) is None


class TestToDate:
    def test_datetime_은_날짜만_취한다(self):
        assert to_date(_dt.datetime(2026, 3, 4, 19, 17)) == DATE

    def test_date_는_그대로(self):
        assert to_date(DATE) == DATE

    def test_ISO_문자열을_파싱한다(self):
        assert to_date(" 2026-03-04 ") == DATE

    @pytest.mark.parametrize("raw", ["", "   ", "2026/03/04", "26-03-04", "2026-13-45", "어제"])
    def test_형식이_깨지면_지어내지_않는다(self, raw):
        assert to_date(raw) is None

    def test_알_수_없는_타입은_None(self):
        assert to_date(20260304) is None
        assert to_date(None) is None


class TestExtractedReceiptInvariants:
    def test_총액은_양수여야_한다(self):
        for amount in (Decimal("0"), Decimal("-1")):
            with pytest.raises(ValueError, match="총액은 양수"):
                ExtractedReceipt(None, None, amount, Decimal("0.9"))

    @pytest.mark.parametrize("confidence", [Decimal("-0.1"), Decimal("1.1"), None])
    def test_총액_신뢰도는_0에서_1_사이여야_한다(self, confidence):
        with pytest.raises(ValueError, match="총액 판독 신뢰도"):
            ExtractedReceipt(None, None, Decimal("12300"), confidence)

    def test_거래일이_없는데_신뢰도가_있으면_거부한다(self):
        # 없는 필드에 신뢰도를 붙이면 판정이 흔들린다.
        with pytest.raises(ValueError, match="거래일 신뢰도"):
            ExtractedReceipt(None, None, Decimal("12300"), Decimal("0.9"), Decimal("0.5"))

    def test_거래일이_있는데_신뢰도가_없으면_거부한다(self):
        with pytest.raises(ValueError, match="거래일 판독 신뢰도"):
            ExtractedReceipt(None, DATE, Decimal("12300"), Decimal("0.9"), None)

    def test_공백_상호명은_판독실패와_같게_정규화된다(self):
        receipt = ExtractedReceipt("   ", None, Decimal("12300"), Decimal("0.9"))
        assert receipt.merchant_name is None

    def test_상호명은_앞뒤_공백을_턴다(self):
        receipt = ExtractedReceipt(" 스타벅스 ", None, Decimal("12300"), Decimal("0.9"))
        assert receipt.merchant_name == "스타벅스"

    def test_가장_못_믿는_필드의_신뢰도를_돌려준다(self):
        both = ExtractedReceipt(None, DATE, Decimal("12300"), Decimal("0.98"), Decimal("0.55"))
        assert both.weakest_confidence == Decimal("0.55")

        amount_only = ExtractedReceipt(None, None, Decimal("12300"), Decimal("0.7"))
        assert amount_only.weakest_confidence == Decimal("0.7")
