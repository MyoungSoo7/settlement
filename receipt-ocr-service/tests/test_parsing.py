"""OCR 텍스트 → 영수증 필드 파싱 테스트.

이 파서가 Phase 1 의 전부다. OCR 엔진은 '글자와 박스' 까지만 주고, **무엇이 총액인가**는
도메인 지식으로 정해야 한다.

핵심 설계는 baseline 이 틀렸던 지점에서 나왔다: 신뢰도가 추출 전체에 대한 스칼라 하나라
쉬운 필드(총액)의 확신이 어려운 필드(거래일)의 불확실성을 덮었다. 그래서 여기서는
**필드마다 자기 신뢰도**를 들고 다닌다.
"""

from __future__ import annotations

import datetime as _dt
from decimal import Decimal

import pytest

from receipt_ocr.providers.parsing import (
    LARGEST_PENALTY,
    STRUCTURAL_BONUS,
    OcrLine,
    ParseFailed,
    find_amount,
    find_date,
    parse_receipt,
)


def line(text: str, confidence: float = 0.9, height: float = 20.0, top: float = 0.0) -> OcrLine:
    return OcrLine(text=text, confidence=confidence, height=height, top=top)


#: 실제 RapidOCR 출력을 본뜬 최소 영수증 — 한글은 오독되고 숫자만 살아 있는 상태.
REALISTIC = [
    line("：840-52-33203", 0.81, 16.0, 100),
    line("：02-987-9007", 0.90, 20.0, 160),
    line("2H :2026-03-09 19:17:26", 0.82, 21.0, 200),
    line("升二也空：4719-****_****7937", 0.75, 21.0, 230),
    line("：25321353", 0.86, 20.0, 260),
    line("27,300", 0.76, 20.0, 320),
    line("36,800", 0.84, 21.0, 350),
    line("58,273", 0.79, 21.0, 420),
    line("5,827", 0.80, 23.0, 450),
    line("64,100别", 0.73, 28.0, 500),
]


class TestFindAmount:
    def test_공급가액과_부가세의_합이_되는_금액을_총액으로_고른다(self):
        # 구조적 검증 — 한국 영수증은 공급가액 + 부가세 = 합계 다. 이게 맞아떨어지면
        # 글자 크기 추측보다 훨씬 강한 근거다(LLM 은 이 검산을 하지 않는다).
        found = find_amount(REALISTIC)
        assert found.value == Decimal("64100")
        assert found.method == "structural"

    def test_구조가_안_맞으면_가장_큰_글씨의_금액을_고른다(self):
        # 합계 줄은 다른 줄보다 큰 폰트로 찍힌다.
        lines = [line("12,000", 0.9, 18.0), line("99,900", 0.9, 30.0), line("3,000", 0.9, 18.0)]
        found = find_amount(lines)
        assert found.value == Decimal("99900")
        assert found.method == "largest"

    def test_금액이_하나도_없으면_None(self):
        assert find_amount([line("이용해 주셔서 감사합니다", 0.9)]) is None

    def test_필드_자신의_신뢰도를_들고_온다(self):
        # 총액 토큰의 OCR 점수여야 한다 — 다른 줄의 점수가 섞이면 안 된다.
        # 합계 토큰의 OCR 점수 0.73 에 구조 검증 보너스가 더해진 값.
        assert find_amount(REALISTIC).confidence == pytest.approx(0.73 + STRUCTURAL_BONUS)

    def test_구조검증이_없으면_토큰_점수를_그대로_쓴다(self):
        lines = [line("12,000", 0.41, 18.0), line("99,900", 0.62, 30.0)]
        found = find_amount(lines)
        assert found.method == "largest"
        # 구조 검증이 실패한 추측이라 벌점이 붙는다 — 모를 때는 리뷰로 보내는 쪽이 싸다.
        assert found.confidence == pytest.approx(0.62 - LARGEST_PENALTY)

    def test_구조검증_실패는_임계_아래로_떨어뜨린다(self):
        # OCR 점수가 높아도(0.95) 구조가 안 맞으면 운영 임계(0.80)를 넘지 못해야 한다.
        # 실측에서 부가세를 합계로 잘못 골라 놓고 0.76 을 주장한 경우가 있었다.
        lines = [line("1,641", 0.95, 30.0), line("18,048", 0.95, 20.0)]
        assert find_amount(lines).confidence < 0.80

    @pytest.mark.parametrize("noise", [
        "2026-03-09 19:17:26",   # 거래일시
        "02-987-9007",           # 전화번호
        "840-52-33203",          # 사업자번호
        "4719-****-****-7937",   # 카드번호
        "25321353",              # 승인번호
    ])
    def test_금액이_아닌_숫자는_후보가_아니다(self, noise):
        # 콤마 3자리 묶음만 금액으로 본다 — 영수증은 금액을 항상 그렇게 찍는다.
        assert find_amount([line(noise, 0.95, 30.0)]) is None

    def test_원_표기가_붙어도_읽는다(self):
        assert find_amount([line("64,100원", 0.9, 28.0)]).value == Decimal("64100")

    def test_할인_음수줄은_총액_후보가_아니다(self):
        # "-1,152" 는 차감액이다. 총액으로 잡히면 양수 검증에서 터진다.
        lines = [line("-1,152", 0.9, 30.0), line("18,048", 0.9, 20.0)]
        assert find_amount(lines).value == Decimal("18048")

    def test_할인_영수증도_구조검증이_성립한다(self):
        # 공급가액 16,407 + 부가세 1,641 = 18,048 (합계)
        lines = [
            line("19,200", 0.9, 20.0), line("-1,152", 0.9, 20.0),
            line("16,407", 0.9, 20.0), line("1,641", 0.9, 20.0),
            line("18,048", 0.9, 26.0),
        ]
        found = find_amount(lines)
        assert found.value == Decimal("18048")
        assert found.method == "structural"


class TestFindDate:
    @pytest.mark.parametrize("text,expected", [
        ("2H :2026-03-09 19:17:26", _dt.date(2026, 3, 9)),
        ("거래일시 : 2026/03/12 22:25", _dt.date(2026, 3, 12)),
        ("2026年 03월 11일 00:52", _dt.date(2026, 3, 11)),
        ("26.03.04 21:30:15", _dt.date(2026, 3, 4)),
    ])
    def test_POS_마다_다른_날짜_표기를_읽는다(self, text, expected):
        assert find_date([line(text, 0.9)]).value == expected

    def test_한글이_오독돼도_숫자로_읽어낸다(self):
        # 한글 인식이 무너져도 거래일은 숫자라 살아난다 — 이 파서의 전제다.
        assert find_date([line("刀래일人 :2026-03-09 19:17:26", 0.8)]).value == _dt.date(2026, 3, 9)

    @pytest.mark.parametrize("noise", ["02-987-9007", "840-52-33203", "25321353",
                                       "4719-****-****-7937", "27,300"])
    def test_날짜가_아닌_숫자를_날짜로_읽지_않는다(self, noise):
        assert find_date([line(noise, 0.95)]) is None

    @pytest.mark.parametrize("text,expected", [
        # 실제 RapidOCR 출력에서 관측된 훼손들 — 공백이 사라지고 앞에 잡음 숫자가 붙는다.
        ("22026/03/1900:52", _dt.date(2026, 3, 19)),
        ("2026/03/1900:52", _dt.date(2026, 3, 19)),
        ("2026-03-0919:17:26", _dt.date(2026, 3, 9)),
        ("20260309", _dt.date(2026, 3, 9)),
    ])
    def test_OCR_이_구분자를_먹거나_잡음을_붙여도_읽는다(self, text, expected):
        assert find_date([line(text, 0.9)]).value == expected

    def test_시각이_함께_있는_줄을_더_믿는다(self):
        # 영수증의 거래일시에는 시각이 붙는다. 승인번호 같은 긴 숫자에서 우연히 날짜 모양이
        # 나오는 것보다, 시각을 동반한 쪽이 진짜 거래일일 가능성이 높다.
        lines = [line("20450612", 0.95), line("2026-03-11 00:52", 0.60)]
        assert find_date(lines).value == _dt.date(2026, 3, 11)

    def test_말이_안_되는_날짜는_버린다(self):
        assert find_date([line("2026-13-45 10:00", 0.9)]) is None

    def test_날짜가_없으면_None(self):
        # 지어내지 않는다 — 판독 불가는 리뷰로 흐른다.
        assert find_date([line("64,100", 0.9)]) is None

    def test_후보가_여럿이면_신뢰도가_높은_쪽(self):
        lines = [line("2020-08-11", 0.40), line("2026-03-11", 0.88)]
        assert find_date(lines).value == _dt.date(2026, 3, 11)

    def test_필드_자신의_신뢰도를_들고_온다(self):
        assert find_date([line("2026-03-09 19:17", 0.62)]).confidence == pytest.approx(0.62)


class TestParseReceipt:
    def test_전체_파싱(self):
        parsed = parse_receipt(REALISTIC)
        assert parsed.extracted.total_amount == Decimal("64100")
        assert parsed.extracted.transaction_date == _dt.date(2026, 3, 9)

    def test_총액을_못_읽으면_지어내지_않고_끊는다(self):
        with pytest.raises(ParseFailed):
            parse_receipt([line("이용해 주셔서 감사합니다", 0.9)])

    def test_거래일을_못_읽어도_총액만_있으면_추출된다(self):
        parsed = parse_receipt([line("64,100", 0.9, 28.0)])
        assert parsed.extracted.transaction_date is None
        assert parsed.extracted.total_amount == Decimal("64100")


class TestPerFieldConfidence:
    """baseline 의 치명 오류를 구조적으로 막는 부분 — 여기가 Phase 1 의 요점이다."""

    def test_어려운_필드가_전체_신뢰도를_끌어내린다(self):
        # 총액은 또렷한데(0.97) 날짜가 흐릿하면(0.35) 전체 신뢰도는 낮아야 한다.
        # baseline 은 여기서 0.98 을 주장해 틀린 날짜로 MISMATCHED 종결을 냈다.
        parsed = parse_receipt([line("30,810", 0.97, 28.0), line("2020-08-11", 0.35)])
        # 금액이 하나뿐이라 구조 검증이 못 서고 largest 벌점을 받는다(0.97 - 0.25).
        assert parsed.amount_confidence == pytest.approx(0.97 - LARGEST_PENALTY)
        assert parsed.date_confidence == pytest.approx(0.35)
        # 그래도 전체는 더 못 믿는 쪽(날짜 0.35)이 대표한다.
        assert parsed.extracted.confidence == Decimal("0.35")

    def test_날짜가_없으면_총액_신뢰도만_쓴다(self):
        # 날짜 None 은 그 자체로 리뷰행이다 — 없는 필드 때문에 총액 신뢰도까지 깎지 않는다.
        parsed = parse_receipt([line("30,810", 0.97, 28.0)])
        assert parsed.date_confidence is None
        assert parsed.extracted.confidence == Decimal(str(round(0.97 - LARGEST_PENALTY, 4)))

    def test_구조검증된_총액은_날짜가_없어도_임계를_넘는다(self):
        # 공급가액 28,009 + 부가세 2,801 = 30,810 — 날짜를 못 읽어도 총액은 믿을 수 있다.
        # (매처가 날짜 None 을 이유로 리뷰로 보내는 건 별개 경로다.)
        lines = [line("28,009", 0.90, 20.0), line("2,801", 0.90, 20.0), line("30,810", 0.90, 28.0)]
        assert parse_receipt(lines).extracted.confidence >= Decimal("0.80")

    def test_구조검증에_성공하면_총액_신뢰도가_올라간다(self):
        # 공급가액+부가세=합계 가 맞아떨어지는 건 OCR 점수와 독립인 추가 근거다.
        weak = [line("58,273", 0.55, 20.0), line("5,827", 0.55, 20.0), line("64,100", 0.55, 28.0)]
        parsed = parse_receipt(weak)
        assert parsed.amount_confidence > 0.55
        assert parsed.amount_method == "structural"

    def test_신뢰도는_0과_1_사이로_묶인다(self):
        strong = [line("58,273", 0.99, 20.0), line("5,827", 0.99, 20.0), line("64,100", 0.99, 28.0)]
        assert parse_receipt(strong).extracted.confidence <= Decimal("1")
