"""대사 규칙 테스트 — card-service ``ExpenseReceiptMatcher`` 의 정책을 케이스로 못박는다.

각 테스트는 Java 매처 주석에 명시된 정책 문장에 1:1 대응한다. 여기가 깨지면 파이썬 채점기가
운영과 다른 판정을 내리고 있다는 뜻이라, 평가 리포트의 숫자를 믿을 수 없다.
"""

from __future__ import annotations

import datetime as _dt
from decimal import Decimal

import pytest

from receipt_ocr.domain.extracted import ExtractedReceipt
from receipt_ocr.domain.matcher import Outcome, decide

THRESHOLD = Decimal("0.80")

#: 매입 시각 기준점 — KST 로 2026-03-04 21:30.
CAPTURED_AT = _dt.datetime(2026, 3, 4, 12, 30, tzinfo=_dt.timezone.utc)
CAPTURED_DATE = _dt.date(2026, 3, 4)
CAPTURED_AMOUNT = Decimal("12300")


def extraction(
    *,
    amount: str = "12300",
    date: _dt.date | None = CAPTURED_DATE,
    confidence: str = "0.95",
    merchant: str | None = "김밥천국 강남점",
) -> ExtractedReceipt:
    return ExtractedReceipt(
        merchant_name=merchant,
        transaction_date=date,
        total_amount=Decimal(amount),
        confidence=Decimal(confidence),
    )


def outcome(**kwargs) -> Outcome:
    return decide(extraction(**kwargs), CAPTURED_AMOUNT, CAPTURED_AT, THRESHOLD).outcome


class TestConfidenceGate:
    """정책 1 — 신뢰도 미달은 값 대조보다 **먼저** 리뷰로 보낸다."""

    def test_신뢰도_미달이면_값이_다_맞아도_리뷰(self):
        assert outcome(confidence="0.79") is Outcome.NEEDS_REVIEW

    def test_신뢰도가_임계와_같으면_통과한다_경계(self):
        # "미만이면" 이므로 임계값 자체는 리뷰가 아니다.
        assert outcome(confidence="0.80") is Outcome.MATCHED

    def test_신뢰도_미달이_총액_불일치보다_우선한다(self):
        # 판정 순서가 곧 정책이다 — 믿을 수 없는 값으로 불일치를 선고하면
        # 멀쩡한 영수증이 되돌리기 어려운 종결(MISMATCHED)로 떨어진다.
        assert outcome(confidence="0.10", amount="99999") is Outcome.NEEDS_REVIEW


class TestAmount:
    """정책 2 — 총액은 정확 일치. 허용 오차를 두는 순간 그만큼 증빙 없는 지출이 통과한다."""

    def test_총액_일치_시_매치(self):
        assert outcome() is Outcome.MATCHED

    @pytest.mark.parametrize("amount", ["12301", "12299"])
    def test_총액_1원_차이도_불일치_경계(self, amount):
        assert outcome(amount=amount) is Outcome.MISMATCHED

    def test_소수점_표기_차이는_불일치가_아니다(self):
        # 12300 과 12300.00 은 같은 금액이다 — BigDecimal.compareTo 대응(equals 아님).
        assert outcome(amount="12300.00") is Outcome.MATCHED


class TestTransactionDate:
    """정책 3 — 매입일(KST) ±1일. 판독 불가는 불일치가 아니라 리뷰다."""

    @pytest.mark.parametrize(
        "date",
        [_dt.date(2026, 3, 3), _dt.date(2026, 3, 4), _dt.date(2026, 3, 5)],
    )
    def test_매입일_전후_1일까지_매치_경계(self, date):
        assert outcome(date=date) is Outcome.MATCHED

    @pytest.mark.parametrize("date", [_dt.date(2026, 3, 2), _dt.date(2026, 3, 6)])
    def test_2일_차이는_불일치_경계_바로_밖(self, date):
        assert outcome(date=date) is Outcome.MISMATCHED

    def test_거래일_판독_불가는_리뷰지_불일치가_아니다(self):
        assert outcome(date=None) is Outcome.NEEDS_REVIEW

    def test_매입일은_UTC가_아니라_KST로_환산한다(self):
        # 2026-03-04T16:00Z = KST 2026-03-05 01:00 — 매입일은 3/5 다.
        # UTC 로 잘못 환산하면 3/4 가 되어 3/6 영수증이 오판된다.
        late_night = _dt.datetime(2026, 3, 4, 16, 0, tzinfo=_dt.timezone.utc)
        decision = decide(
            extraction(date=_dt.date(2026, 3, 6)), CAPTURED_AMOUNT, late_night, THRESHOLD
        )
        assert decision.outcome is Outcome.MATCHED


class TestMerchantNameIsIgnored:
    """상호명은 판정에 쓰지 않는다 — OCR 상호 표기는 가맹점 등록명과 상시 불일치한다."""

    @pytest.mark.parametrize("merchant", [None, "", "전혀 다른 상호"])
    def test_상호명이_무엇이든_판정은_바뀌지_않는다(self, merchant):
        assert outcome(merchant=merchant) is Outcome.MATCHED


class TestInputValidation:
    def test_필수_입력_누락은_거부한다(self):
        with pytest.raises(ValueError):
            decide(extraction(), None, CAPTURED_AT, THRESHOLD)

    def test_판정_사유가_비어있지_않다(self):
        # note 는 리뷰 큐 화면에 그대로 뜬다 — 왜 걸렸는지 사람이 읽을 수 있어야 한다.
        decision = decide(extraction(amount="1"), CAPTURED_AMOUNT, CAPTURED_AT, THRESHOLD)
        assert decision.outcome is Outcome.MISMATCHED
        assert decision.note.strip()
