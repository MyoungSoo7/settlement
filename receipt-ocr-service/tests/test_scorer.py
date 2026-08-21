"""평가 채점기 테스트.

채점기가 틀리면 이후 모든 판단(파인튜닝이 baseline 을 이겼는가)이 통째로 무의미해진다.
그래서 손으로 검산 가능한 작은 케이스로 못박는다.
"""

from __future__ import annotations

import datetime as _dt
from decimal import Decimal

import pytest

from receipt_ocr.domain.extracted import ExtractedReceipt
from receipt_ocr.domain.matcher import Outcome
from receipt_ocr.eval.scorer import (
    CaptureRef,
    GoldenCase,
    Prediction,
    expected_calibration_error,
    percentile,
    score,
)

THRESHOLD = Decimal("0.80")
CAPTURED_AT = _dt.datetime(2026, 3, 4, 12, 30, tzinfo=_dt.timezone.utc)
CAPTURED_DATE = _dt.date(2026, 3, 4)


def capture(amount: str = "12300") -> CaptureRef:
    return CaptureRef(capture_id="CAP-1", amount=Decimal(amount), captured_at=CAPTURED_AT)


def case(case_id: str, *, truth_amount: str = "12300", truth_date=CAPTURED_DATE,
         capture_amount: str = "12300") -> GoldenCase:
    return GoldenCase(
        case_id=case_id,
        capture=capture(capture_amount),
        truth_amount=Decimal(truth_amount),
        truth_date=truth_date,
    )


def prediction(case_id: str, *, amount: str = "12300", date=CAPTURED_DATE,
               confidence: str = "0.95", latency_ms: float = 100.0) -> Prediction:
    return Prediction(
        case_id=case_id,
        extracted=ExtractedReceipt(
            merchant_name="상호",
            transaction_date=date,
            total_amount=Decimal(amount),
            amount_confidence=Decimal(confidence),
            date_confidence=None if date is None else Decimal(confidence),
        ),
        latency_ms=latency_ms,
    )


class TestTruthOutcome:
    """정답 판정은 '신뢰도 1.0 인 완벽한 추출' 을 대사 규칙에 통과시켜 얻는다."""

    def test_영수증이_매입과_맞으면_정답은_매치(self):
        assert case("c1").truth_outcome(THRESHOLD) is Outcome.MATCHED

    def test_금액이_실제로_다른_영수증의_정답은_불일치(self):
        # 위조·오첨부 시나리오 — 모델이 이걸 MATCHED 로 통과시키면 증빙 없는 지출이 승인된다.
        adversarial = case("c2", truth_amount="99000", capture_amount="12300")
        assert adversarial.truth_outcome(THRESHOLD) is Outcome.MISMATCHED

    def test_날짜가_실제로_안_찍힌_영수증의_정답은_리뷰(self):
        assert case("c3", truth_date=None).truth_outcome(THRESHOLD) is Outcome.NEEDS_REVIEW


class TestOutcomeAccuracy:
    def test_전부_맞히면_정확도_1(self):
        report = score([case("c1")], [prediction("c1")], THRESHOLD)
        assert report.n == 1
        assert report.accuracy == pytest.approx(1.0)
        assert report.weighted_cost == 0

    def test_판정이_다르면_정확도에_반영된다(self):
        cases = [case("c1"), case("c2")]
        preds = [prediction("c1"), prediction("c2", amount="9999")]
        report = score(cases, preds, THRESHOLD)
        assert report.accuracy == pytest.approx(0.5)


class TestCriticalErrors:
    """두 종류의 치명 오류는 따로 센다 — 평균 정확도에 묻히면 안 된다."""

    def test_멀쩡한_영수증을_오종결하면_치명(self):
        # 정답 MATCHED 인데 모델이 총액을 잘못 읽어 MISMATCHED 를 선고 → 되돌리려면 재첨부뿐.
        report = score([case("c1")], [prediction("c1", amount="9999")], THRESHOLD)
        assert report.critical_false_mismatch == 1
        assert report.critical_false_match == 0

    def test_증빙없는_지출을_통과시키면_치명(self):
        # 실제로는 금액이 다른 영수증(99000)인데 모델이 매입 금액(12300)을 읽어내 MATCHED.
        adversarial = case("c2", truth_amount="99000", capture_amount="12300")
        report = score([adversarial], [prediction("c2", amount="12300")], THRESHOLD)
        assert report.critical_false_match == 1
        assert report.critical_false_mismatch == 0

    def test_리뷰로_보낸_오답은_치명이_아니다(self):
        # 자신 없으면 리뷰로 보내는 건 설계된 안전 경로다 — 비용은 있지만 치명은 아니다.
        report = score([case("c1")], [prediction("c1", confidence="0.10")], THRESHOLD)
        assert report.critical_false_mismatch == 0
        assert report.critical_false_match == 0
        assert report.review_rate == pytest.approx(1.0)
        assert 0 < report.weighted_cost < 25


class TestExtractionFailure:
    def test_추출_실패는_UNAVAILABLE_로_집계된다(self):
        failed = Prediction(case_id="c1", extracted=None, error="503", latency_ms=50.0)
        report = score([case("c1")], [failed], THRESHOLD)
        assert report.unavailable_rate == pytest.approx(1.0)
        assert report.accuracy == pytest.approx(0.0)

    def test_예측이_아예_없는_케이스도_UNAVAILABLE_이다(self):
        # 러너가 뻗어서 결과가 빠진 걸 '평가 대상 축소' 로 처리하면 점수가 부풀려진다.
        report = score([case("c1"), case("c2")], [prediction("c1")], THRESHOLD)
        assert report.n == 2
        assert report.unavailable_rate == pytest.approx(0.5)


class TestFieldMetrics:
    def test_총액_정확일치율과_거래일_허용내_일치율(self):
        cases = [case("c1"), case("c2")]
        preds = [
            prediction("c1"),                                            # 총액 O, 날짜 O
            prediction("c2", amount="9999", date=_dt.date(2026, 3, 5)),  # 총액 X, 날짜 O(±1일)
        ]
        report = score(cases, preds, THRESHOLD)
        assert report.amount_exact_rate == pytest.approx(0.5)
        assert report.date_within_tolerance_rate == pytest.approx(1.0)


class TestCalibration:
    def test_ECE_단일구간_손검산(self):
        # 신뢰도 0.9 를 4번 주장했는데 3번만 맞았다 → |0.75 - 0.9| = 0.15
        pairs = [(0.9, True), (0.9, True), (0.9, True), (0.9, False)]
        assert expected_calibration_error(pairs, bins=10) == pytest.approx(0.15)

    def test_ECE_완벽교정은_0(self):
        pairs = [(1.0, True), (1.0, True), (0.0, False), (0.0, False)]
        assert expected_calibration_error(pairs, bins=10) == pytest.approx(0.0)

    def test_ECE_두구간_가중평균_손검산(self):
        # 구간A(0.9): 2건 중 1건 정답 → |0.5-0.9|=0.4, 가중 2/4
        # 구간B(0.1): 2건 중 0건 정답 → |0.0-0.1|=0.1, 가중 2/4
        # ECE = 0.5*0.4 + 0.5*0.1 = 0.25
        pairs = [(0.9, True), (0.9, False), (0.1, False), (0.1, False)]
        assert expected_calibration_error(pairs, bins=10) == pytest.approx(0.25)

    def test_ECE_는_구간_크기로_가중한다_손검산(self):
        # 구간 크기가 다를 때만 가중 여부가 드러난다 — 위 2:2 케이스로는 구분되지 않는다.
        # 구간A(0.9): 3건 중 2건 정답 → |2/3 - 0.9| = 0.23333, 가중 3/4
        # 구간B(0.1): 1건 중 0건 정답 → |0.0 - 0.1| = 0.1,     가중 1/4
        # 가중 ECE = 0.75*0.23333 + 0.25*0.1 = 0.2   (단순 평균이면 0.16667)
        pairs = [(0.9, True), (0.9, True), (0.9, False), (0.1, False)]
        assert expected_calibration_error(pairs, bins=10) == pytest.approx(0.2)

    def test_표본이_없으면_0(self):
        assert expected_calibration_error([], bins=10) == pytest.approx(0.0)


class TestPercentile:
    def test_최근접순위법_p95(self):
        values = [float(i) for i in range(1, 101)]  # 1..100
        assert percentile(values, 95) == pytest.approx(95.0)

    def test_단일값(self):
        assert percentile([42.0], 95) == pytest.approx(42.0)

    def test_빈_입력은_0(self):
        assert percentile([], 95) == pytest.approx(0.0)


class TestReportIntegrity:
    def test_혼동행렬_합계가_전체_건수와_같다(self):
        cases = [case("c1"), case("c2"), case("c3")]
        preds = [prediction("c1"), prediction("c2", amount="1"), Prediction("c3", None)]
        report = score(cases, preds, THRESHOLD)
        assert sum(report.confusion.values()) == report.n == 3

    def test_알_수_없는_case_id_예측은_거부한다(self):
        # 골든셋에 없는 id 가 섞이면 조용히 무시되는 대신 터져야 한다.
        with pytest.raises(ValueError):
            score([case("c1")], [prediction("없는케이스")], THRESHOLD)

    def test_중복_예측은_거부한다(self):
        with pytest.raises(ValueError):
            score([case("c1")], [prediction("c1"), prediction("c1")], THRESHOLD)
