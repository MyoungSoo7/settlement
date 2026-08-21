"""합성 생성기 테스트.

가장 중요한 속성은 **시나리오가 의도한 정답 판정을 실제로 만들어내는가** 다. 여기가 어긋나면
골든셋의 라벨이 틀린 것이라, 모델을 아무리 잘 만들어도 점수가 거짓이 된다.
"""

from __future__ import annotations

import datetime as _dt
from decimal import Decimal

import pytest

from receipt_ocr.domain.matcher import Outcome
from receipt_ocr.eval.goldenset import to_golden_case
from receipt_ocr.synth.generator import RenderCondition, Scenario, generate

THRESHOLD = Decimal("0.80")

#: 시나리오 → 이 시나리오가 만들어내야 하는 정답 판정.
EXPECTED = {
    Scenario.CLEAN: Outcome.MATCHED,
    Scenario.NEXT_DAY: Outcome.MATCHED,
    Scenario.NO_DATE: Outcome.NEEDS_REVIEW,
    Scenario.AMOUNT_TAMPERED: Outcome.MISMATCHED,
    Scenario.STALE_DATE: Outcome.MISMATCHED,
}


class TestScenarioLabels:
    @pytest.mark.parametrize("scenario", list(Scenario))
    def test_시나리오는_의도한_정답판정을_만든다(self, scenario):
        receipts = generate(12, scenarios=[scenario])
        outcomes = {
            to_golden_case(r).truth_outcome(THRESHOLD) for r in receipts
        }
        assert outcomes == {EXPECTED[scenario]}, f"{scenario}: {outcomes}"

    def test_기본_생성은_세_판정을_모두_포함한다(self):
        # 작은 셋에서 MISMATCHED 가 0건이면 치명 오류 지표를 아예 측정할 수 없다.
        cases = [to_golden_case(r) for r in generate(len(Scenario) * 2)]
        outcomes = {c.truth_outcome(THRESHOLD) for c in cases}
        assert outcomes == {Outcome.MATCHED, Outcome.NEEDS_REVIEW, Outcome.MISMATCHED}


class TestDeterminism:
    def test_같은_시드는_같은_셋을_만든다(self):
        # 리플레이 가능한 비교의 전제 — 시드가 같은데 셋이 달라지면 모델 간 비교가 무의미해진다.
        first = generate(8, seed=42)
        second = generate(8, seed=42)
        assert [r.printed_total for r in first] == [r.printed_total for r in second]
        assert [r.merchant_name for r in first] == [r.merchant_name for r in second]
        assert [r.printed_datetime for r in first] == [r.printed_datetime for r in second]

    def test_다른_시드는_다른_셋을_만든다(self):
        assert [r.printed_total for r in generate(8, seed=1)] != [
            r.printed_total for r in generate(8, seed=2)
        ]


class TestAmountIntegrity:
    def test_총액은_품목합계에서_할인을_뺀_값이다(self):
        for receipt in generate(20):
            expected = sum((item.amount for item in receipt.items), Decimal("0"))
            assert receipt.printed_total == expected - receipt.discount, receipt.case_id

    def test_할인이_있는_케이스가_존재한다(self):
        # 금액 후보를 늘리는 축이다 — 0건이면 '무엇이 총액인가' 를 아예 시험하지 못한다.
        discounted = [r for r in generate(30) if r.discount > 0]
        assert discounted, "할인 케이스가 하나도 없습니다"
        for receipt in discounted:
            assert receipt.subtotal > receipt.printed_total, receipt.case_id

    def test_공급가액과_부가세의_합은_총액이다(self):
        # 영수증에 인쇄되는 세 숫자가 서로 안 맞으면 모델이 어느 걸 총액으로 볼지 흔들린다.
        for receipt in generate(20):
            assert receipt.supply_amount + receipt.vat == receipt.printed_total, receipt.case_id

    def test_금액은_전부_양수다(self):
        for receipt in generate(20):
            assert receipt.printed_total > 0
            assert receipt.captured_amount > 0

    def test_조작_시나리오는_영수증과_매입_금액이_실제로_다르다(self):
        for receipt in generate(10, scenarios=[Scenario.AMOUNT_TAMPERED]):
            assert receipt.printed_total != receipt.captured_amount, receipt.case_id


class TestVariation:
    def test_거래일시_표기_형식이_섞여_있다(self):
        # POS 마다 날짜 표기가 다르다 — 한 형식에만 맞춘 모델이 통과하면 안 된다.
        assert len({r.date_format for r in generate(12)}) > 1

    def test_렌더_조건이_고르게_섞여_있다(self):
        full_cross = len(Scenario) * len(RenderCondition)
        conditions = {r.condition for r in generate(full_cross)}
        assert conditions == set(RenderCondition)

    def test_시나리오와_촬영조건이_결착되어_있지_않다(self):
        # 두 축이 같은 주기로 돌면 완전히 결착되어(CLEAN 은 항상 PRISTINE...) 조건별 단면이
        # 사실은 시나리오를 재게 된다. 실제로 그런 버그가 있었다.
        pairs: dict[Scenario, set[RenderCondition]] = {}
        for receipt in generate(len(Scenario) * len(RenderCondition)):
            pairs.setdefault(receipt.scenario, set()).add(receipt.condition)
        for scenario, conditions in pairs.items():
            assert len(conditions) > 1, f"{scenario} 가 {conditions} 하나에만 묶여 있습니다"

    def test_케이스_id_는_고유하다(self):
        receipts = generate(30)
        assert len({r.case_id for r in receipts}) == 30


class TestInputValidation:
    @pytest.mark.parametrize("count", [0, -1])
    def test_건수는_양수여야_한다(self, count):
        with pytest.raises(ValueError):
            generate(count)


class TestNextDayBoundary:
    def test_심야결제는_정확히_하루_차이여야_한다(self):
        # 이틀 차가 나면 정답이 MISMATCHED 로 뒤집혀 라벨이 거짓이 된다.
        for receipt in generate(10, scenarios=[Scenario.NEXT_DAY]):
            captured_date = receipt.captured_at.date()
            assert (receipt.printed_date - captured_date) == _dt.timedelta(days=1), receipt.case_id
