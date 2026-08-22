"""다중 패스 중재 테스트 — 원본과 전처리본을 둘 다 읽고 무엇을 채택할지.

전처리는 양날이다. Phase 1 실측에서 총액 판독을 67.6% → 79.4% 로 올리면서 가중 오류비용은
57 → 79 로 악화시켰다(더 자신 있게 읽은 값이 임계를 넘어 종결로 갔다). **하나를 고르지 말고
둘 다 읽은 뒤, 구조 검증이라는 독립 근거로 고르는 것**이 이 모듈의 발상이다.
"""

from __future__ import annotations

from decimal import Decimal

import pytest

from receipt_ocr.providers.parsing import OcrLine, ParseFailed, choose_pass, parse_receipt


def line(text: str, confidence: float = 0.9, height: float = 20.0) -> OcrLine:
    return OcrLine(text=text, confidence=confidence, height=height)


#: 구조 검증이 서는 판독 — 공급가액 58,273 + 부가세 5,827 = 합계 64,100
STRUCTURAL = [line("58,273", 0.70), line("5,827", 0.70), line("64,100", 0.70, 28.0)]

#: 금액 하나를 잘못 읽어 구조가 무너진 판독 — 합계 후보는 더 또렷하게 읽혔다
BROKEN = [line("58,273", 0.95), line("5,821", 0.95), line("64,100", 0.95, 28.0)]


class TestChoosePass:
    def test_구조가_서는_쪽을_고른다_점수가_낮아도(self):
        # OCR 점수는 "이 글자를 이렇게 읽었다" 는 확신일 뿐이다. 금액끼리 산술이 맞아떨어지는 것은
        # 점수와 독립인 근거라, 점수가 낮아도 이쪽이 맞을 가능성이 높다.
        chosen = choose_pass([parse_receipt(BROKEN), parse_receipt(STRUCTURAL)])
        assert chosen.amount_method == "structural"
        assert chosen.extracted.total_amount == Decimal("64100")

    def test_순서가_바뀌어도_같은_것을_고른다(self):
        chosen = choose_pass([parse_receipt(STRUCTURAL), parse_receipt(BROKEN)])
        assert chosen.amount_method == "structural"

    def test_둘_다_구조가_서면_신뢰도가_높은_쪽(self):
        weak = [line("58,273", 0.55), line("5,827", 0.55), line("64,100", 0.55, 28.0)]
        chosen = choose_pass([parse_receipt(weak), parse_receipt(STRUCTURAL)])
        assert chosen.amount_confidence == pytest.approx(parse_receipt(STRUCTURAL).amount_confidence)

    def test_둘_다_구조가_안_서면_신뢰도가_높은_쪽(self):
        a = [line("12,000", 0.40, 30.0)]
        b = [line("99,900", 0.80, 30.0)]
        chosen = choose_pass([parse_receipt(a), parse_receipt(b)])
        assert chosen.extracted.total_amount == Decimal("99900")

    def test_거래일은_읽어낸_쪽에서_가져온다(self):
        # 총액은 A 가 구조로 이겼지만 거래일은 A 가 못 읽고 B 만 읽었다면, 날짜를 버릴 이유가 없다.
        without_date = parse_receipt(STRUCTURAL)
        with_date = parse_receipt(BROKEN + [line("2026-03-09 19:17", 0.88)])
        chosen = choose_pass([without_date, with_date])

        assert chosen.amount_method == "structural"          # 총액은 구조가 선 쪽
        assert chosen.extracted.transaction_date is not None  # 날짜는 읽어낸 쪽
        assert chosen.date_confidence == pytest.approx(0.88)

    def test_날짜를_둘_다_읽었으면_신뢰도가_높은_쪽(self):
        low = parse_receipt(STRUCTURAL + [line("2026-03-09 19:17", 0.40)])
        high = parse_receipt(BROKEN + [line("2026-03-09 19:17", 0.91)])
        assert choose_pass([low, high]).date_confidence == pytest.approx(0.91)

    def test_후보가_하나면_그대로_돌려준다(self):
        only = parse_receipt(STRUCTURAL)
        assert choose_pass([only]) is only

    def test_전부_실패면_끊는다(self):
        # 어느 패스도 총액을 못 읽었으면 지어내지 않는다.
        with pytest.raises(ParseFailed):
            choose_pass([])
