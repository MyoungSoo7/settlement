"""교정 학습·임계 탐색 테스트.

**학습은 학습셋에서, 임계도 학습셋에서.** 이 두 규칙이 깨지면 이후 홀드아웃 수치는 일반화
추정치가 아니게 되는데, 그 사실은 숫자만 봐서는 드러나지 않는다. 그래서 절차 자체를 테스트한다.
"""

from __future__ import annotations

import datetime as _dt
import json
import pathlib
from decimal import Decimal

import pytest

from receipt_ocr.calib import cache
from receipt_ocr.calib.features import AMOUNT_FEATURES, DATE_FEATURES, Sample
from receipt_ocr.calib.sweep import SweepPoint, best, default_grid, sweep
from receipt_ocr.calib.train import _fit, collect, train
from receipt_ocr.domain.extracted import ExtractedReceipt
from receipt_ocr.eval.scorer import CaptureRef, GoldenCase, Prediction

CAPTURED_AT = _dt.datetime(2026, 3, 9, 19, 17, tzinfo=_dt.timezone.utc)
TRUTH_DATE = _dt.date(2026, 3, 9)

#: 공급가액 + 부가세 = 합계 가 서는 줄들 — 파서가 structural 로 채택한다.
STRUCTURAL = [
    {"text": "58,273", "confidence": 0.70, "height": 20.0, "top": 10.0},
    {"text": "5,827", "confidence": 0.70, "height": 20.0, "top": 30.0},
    {"text": "64,100", "confidence": 0.70, "height": 28.0, "top": 50.0},
]
WITH_DATE = STRUCTURAL + [{"text": "2026-03-09 19:17", "confidence": 0.55,
                           "height": 18.0, "top": 70.0}]


def write_cache(cache_dir: pathlib.Path, case_id: str, lines: list[dict]) -> None:
    cache_dir.mkdir(parents=True, exist_ok=True)
    (cache_dir / f"{case_id}.json").write_text(
        json.dumps({"schema_version": cache.SCHEMA_VERSION, "case_id": case_id,
                    "passes": {name: lines for name in cache.PASSES}}, ensure_ascii=False),
        encoding="utf-8",
    )


def golden(case_id: str, *, amount: str = "64100", date=TRUTH_DATE) -> GoldenCase:
    return GoldenCase(
        case_id=case_id,
        capture=CaptureRef(f"CAP-{case_id}", Decimal("64100"), CAPTURED_AT),
        truth_amount=Decimal(amount),
        truth_date=date,
        scenario="정상",
        condition="양호",
    )


class TestCollect:
    def test_캐시가_없는_건은_표본에서_빠진다(self, tmp_path):
        assert collect([golden("C1")], tmp_path) == []

    def test_두_패스_모두_파싱에_실패하면_빠진다(self, tmp_path):
        # 금액 후보가 하나도 없는 판독 — ParseFailed.
        write_cache(tmp_path, "C1", [{"text": "감사합니다", "confidence": 0.9,
                                      "height": 20.0, "top": 10.0}])
        assert collect([golden("C1")], tmp_path) == []

    def test_판독과_정답을_대조해_라벨을_붙인다(self, tmp_path):
        write_cache(tmp_path, "C1", WITH_DATE)

        samples = collect([golden("C1")], tmp_path)

        assert len(samples) == 1
        sample = samples[0]
        assert sample.case_id == "C1"
        assert sample.amount_correct is True
        assert sample.date_correct is True
        assert set(sample.amount) == set(AMOUNT_FEATURES)
        assert set(sample.date) == set(DATE_FEATURES)

    def test_총액이_정답과_다르면_오답으로_라벨한다(self, tmp_path):
        write_cache(tmp_path, "C1", WITH_DATE)

        samples = collect([golden("C1", amount="99999")], tmp_path)

        assert samples[0].amount_correct is False

    def test_거래일이_없으면_거래일_라벨을_붙이지_않는다(self, tmp_path):
        # 거래일 축은 "읽은 건" 만 학습 대상이다 — 안 읽은 건을 오답으로 세면 모델이 왜곡된다.
        write_cache(tmp_path, "C1", STRUCTURAL)

        samples = collect([golden("C1")], tmp_path)

        assert samples[0].date is None
        assert samples[0].date_correct is None

    def test_허용오차_밖의_거래일은_오답이다(self, tmp_path):
        write_cache(tmp_path, "C1", WITH_DATE)

        samples = collect([golden("C1", date=_dt.date(2026, 4, 1))], tmp_path)

        assert samples[0].date_correct is False

    def test_허용오차_안이면_정답이다(self, tmp_path):
        # 매처와 같은 ±1일.
        write_cache(tmp_path, "C1", WITH_DATE)

        samples = collect([golden("C1", date=_dt.date(2026, 3, 10))], tmp_path)

        assert samples[0].date_correct is True


class TestFit:
    def test_한_클래스만_있으면_회귀_대신_상수_헤드를_돌려준다(self):
        # 계수를 지어내는 것보다 관측된 비율을 그대로 내는 편이 정직하다.
        rows = [[0.5] * len(AMOUNT_FEATURES) for _ in range(5)]
        head = _fit(rows, [1] * 5, AMOUNT_FEATURES, regularization=0.3)

        assert head.weights == (0.0,) * len(AMOUNT_FEATURES)
        assert head.trained_on == 5
        # 전부 정답이어도 확률 1.0 을 주장하지 않는다(0.99 로 클램프).
        assert head.probability(dict.fromkeys(AMOUNT_FEATURES, 0.5)) == pytest.approx(0.99, abs=1e-6)

    def test_표본이_비어도_죽지_않는다(self):
        head = _fit([], [], AMOUNT_FEATURES, regularization=0.3)
        assert head.trained_on == 0
        assert 0.0 < head.probability(dict.fromkeys(AMOUNT_FEATURES, 0.0)) < 1.0

    def test_양쪽_클래스가_있으면_실제로_학습한다(self):
        rows = [[float(i % 2)] + [0.0] * (len(AMOUNT_FEATURES) - 1) for i in range(20)]
        labels = [i % 2 for i in range(20)]

        head = _fit(rows, labels, AMOUNT_FEATURES, regularization=0.3)

        assert any(w != 0.0 for w in head.weights), "학습이 상수 헤드로 떨어졌다"
        assert head.trained_on == 20
        assert head.positive_rate == 0.5


class TestTrain:
    def test_두_축을_따로_학습한다(self):
        samples = [
            Sample(
                case_id=f"C{i}",
                amount=dict.fromkeys(AMOUNT_FEATURES, float(i % 2)),
                date=dict.fromkeys(DATE_FEATURES, float(i % 2)),
                amount_correct=bool(i % 2),
                date_correct=bool(i % 2),
            )
            for i in range(20)
        ]

        model = train(samples)

        assert model.amount.feature_names == AMOUNT_FEATURES
        assert model.date.feature_names == DATE_FEATURES
        assert model.amount.trained_on == 20
        assert model.date.trained_on == 20

    def test_거래일이_없는_표본은_거래일_학습에서_제외된다(self):
        samples = [
            Sample(f"C{i}", dict.fromkeys(AMOUNT_FEATURES, 0.5), None, True, None)
            for i in range(5)
        ]

        model = train(samples)

        assert model.amount.trained_on == 5
        assert model.date.trained_on == 0


class TestSweep:
    def _cases_and_predictions(self):
        cases, predictions = [], []
        for i in range(4):
            cases.append(golden(f"C{i}"))
            predictions.append(
                Prediction(
                    f"C{i}",
                    ExtractedReceipt(None, TRUTH_DATE, Decimal("64100"),
                                     Decimal("0.5"), Decimal("0.5")),
                )
            )
        return cases, predictions

    def test_예측은_그대로_두고_정책만_바꿔_본다(self):
        cases, predictions = self._cases_and_predictions()

        points = sweep(cases, predictions, [Decimal("0.4"), Decimal("0.6")])

        assert [p.threshold for p in points] == [Decimal("0.4"), Decimal("0.6")]
        # 임계가 신뢰도(0.5)를 넘어서면 전 건이 리뷰로 간다.
        assert points[0].review_rate == 0.0
        assert points[1].review_rate == 1.0

    def test_격자는_0과_1을_포함하지_않는다(self):
        grid = default_grid("0.05")

        assert grid[0] == Decimal("0.05")
        assert grid[-1] == Decimal("0.95")
        assert Decimal("0") not in grid and Decimal("1") not in grid

    def test_격자_간격을_바꿀_수_있다(self):
        assert default_grid("0.25") == [Decimal("0.25"), Decimal("0.50"), Decimal("0.75")]


class TestBest:
    def point(self, threshold: str, cost: int, critical: int = 0, review: float = 0.0):
        return SweepPoint(Decimal(threshold), cost, 0.9, review, critical)

    def test_가중비용_최소점을_고른다(self):
        points = [self.point("0.5", 30), self.point("0.6", 10), self.point("0.7", 20)]
        assert best(points).threshold == Decimal("0.6")

    def test_비용이_같으면_치명_오류가_적은_쪽(self):
        # 비용이 같아도 되돌릴 수 없는 오종결 1건과 리뷰 25건은 성격이 다르다.
        points = [self.point("0.5", 10, critical=3), self.point("0.6", 10, critical=1)]
        assert best(points).threshold == Decimal("0.6")

    def test_치명도_같으면_리뷰가_적은_쪽(self):
        points = [self.point("0.5", 10, critical=1, review=0.4),
                  self.point("0.6", 10, critical=1, review=0.2)]
        assert best(points).threshold == Decimal("0.6")

    def test_탐색_결과가_비면_거부한다(self):
        with pytest.raises(ValueError, match="비었"):
            best([])

    def test_점_하나는_사람이_읽을_수_있게_찍힌다(self):
        text = str(self.point("0.6", 10, critical=1, review=0.25))
        assert "임계 0.6" in text and "비용" in text and "치명 1" in text
