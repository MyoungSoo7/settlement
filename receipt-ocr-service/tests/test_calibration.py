"""교정 모델 테스트 — OCR 없이 순수 산술만 검증한다.

교정이 틀리면 임계 판정이 통째로 어긋나므로, 손으로 검산 가능한 값으로 못박는다.
"""

from __future__ import annotations

import ast
import json
import math
import pathlib
import types
from decimal import Decimal

import pytest

from receipt_ocr.calib.apply import calibrate
from receipt_ocr.calib.features import AMOUNT_FEATURES, DATE_FEATURES, vector
from receipt_ocr.calib.model import CalibrationModel, Head
from receipt_ocr.providers.parsing import OcrLine, parse_receipt


def head(names, weights, bias, **kw) -> Head:
    return Head(feature_names=tuple(names), weights=tuple(weights), bias=bias, **kw)


def constant_model(amount_p: float, date_p: float) -> CalibrationModel:
    """모든 입력에 고정 확률을 내는 모델 — 적용 경로만 검증할 때 쓴다."""
    logit = lambda p: math.log(p / (1 - p))
    return CalibrationModel(
        amount=head(AMOUNT_FEATURES, [0.0] * len(AMOUNT_FEATURES), logit(amount_p)),
        date=head(DATE_FEATURES, [0.0] * len(DATE_FEATURES), logit(date_p)),
    )


def line(text: str, confidence: float = 0.9, height: float = 20.0) -> OcrLine:
    return OcrLine(text=text, confidence=confidence, height=height)


STRUCTURAL_LINES = [line("58,273", 0.70), line("5,827", 0.70), line("64,100", 0.70, 28.0)]
WITH_DATE = STRUCTURAL_LINES + [line("2026-03-09 19:17", 0.55)]


class TestHead:
    def test_시그모이드_손검산(self):
        # bias 0, weight 1, x=0 → 0.5
        assert head(("a",), (1.0,), 0.0).probability({"a": 0.0}) == pytest.approx(0.5)
        # z = 2 → 1/(1+e^-2) = 0.880797
        assert head(("a",), (1.0,), 0.0).probability({"a": 2.0}) == pytest.approx(0.880797, abs=1e-6)

    def test_여러_특징을_가중합한다(self):
        h = head(("a", "b"), (2.0, -1.0), 0.5)
        # z = 0.5 + 2*1 - 1*3 = -0.5 → 0.377541
        assert h.probability({"a": 1.0, "b": 3.0}) == pytest.approx(0.377541, abs=1e-6)

    @pytest.mark.parametrize("x", [1e6, -1e6])
    def test_극단값에서_터지지_않는다(self, x):
        # 규제가 약한 모델은 큰 계수를 낼 수 있다. math.exp 오버플로로 추출이 죽으면 안 된다.
        p = head(("a",), (1000.0,), 0.0).probability({"a": x})
        assert 0.0 <= p <= 1.0


class TestModelSerialization:
    def test_왕복(self, tmp_path):
        model = constant_model(0.7, 0.3)
        path = tmp_path / "calibration.json"
        model.save(path)
        loaded = CalibrationModel.load(path)

        assert loaded.amount.feature_names == AMOUNT_FEATURES
        assert loaded.amount.probability({n: 0.0 for n in AMOUNT_FEATURES}) == pytest.approx(0.7)

    def test_파일이_없으면_None(self, tmp_path):
        # 교정은 선택 사항 — 없다고 추출이 멈추면 안 된다.
        assert CalibrationModel.load(tmp_path / "없음.json") is None

    def test_버전이_다르면_거부한다(self, tmp_path):
        path = tmp_path / "calibration.json"
        path.write_text(json.dumps({"schema_version": 99, "heads": {}}), encoding="utf-8")
        with pytest.raises(ValueError):
            CalibrationModel.load(path)

    def test_서빙_경로에는_sklearn_numpy_가_없다(self):
        """학습은 무겁게, 서빙은 가볍게 — 운영 이미지에 학습 의존성을 넣지 않는다.

        주석에 이름이 나오는 것은 상관없다. **실제 import** 만 본다.
        """
        serving_modules = [
            "src/receipt_ocr/calib/model.py",
            "src/receipt_ocr/calib/apply.py",
            "src/receipt_ocr/calib/features.py",
            "src/receipt_ocr/providers/parsing.py",
        ]
        heavy = {"sklearn", "numpy", "scipy", "torch", "pandas"}
        for relative in serving_modules:
            tree = ast.parse(pathlib.Path(relative).read_text(encoding="utf-8"))
            imported = set()
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    imported.update(alias.name.split(".")[0] for alias in node.names)
                elif isinstance(node, ast.ImportFrom) and node.module:
                    imported.add(node.module.split(".")[0])
            assert not (imported & heavy), f"{relative} 가 학습 의존성을 import 한다: {imported & heavy}"


class TestCalibrateApplication:
    def test_두_축_모두_교정된다(self):
        parsed = parse_receipt(WITH_DATE)
        result = calibrate(parsed, WITH_DATE, constant_model(0.93, 0.21))

        assert result.extracted.amount_confidence == Decimal("0.93")
        assert result.extracted.date_confidence == Decimal("0.21")

    def test_거래일이_없으면_날짜_교정도_없다(self):
        parsed = parse_receipt(STRUCTURAL_LINES)
        result = calibrate(parsed, STRUCTURAL_LINES, constant_model(0.93, 0.21))

        assert result.extracted.date_confidence is None
        assert result.date_confidence is None

    def test_모델이_없으면_원본_그대로(self):
        parsed = parse_receipt(WITH_DATE)
        assert calibrate(parsed, WITH_DATE, None) is parsed

    def test_판독_근거는_보존된다(self):
        # 감사 때 "왜 그 값을 총액으로 골랐나" 에 답해야 한다 — 교정이 근거를 지우면 안 된다.
        parsed = parse_receipt(WITH_DATE)
        result = calibrate(parsed, WITH_DATE, constant_model(0.5, 0.5))

        assert result.amount_method == parsed.amount_method == "structural"
        assert result.amount_source == parsed.amount_source

    def test_교정은_원점수와_다른_눈금이다(self):
        # 원점수 0.85(구조 보너스 포함)인 판독에 모델이 0.40 을 줄 수 있어야 한다.
        # 같아야 한다면 교정할 이유가 없다.
        parsed = parse_receipt(STRUCTURAL_LINES)
        assert parsed.amount_confidence > 0.80
        assert calibrate(parsed, STRUCTURAL_LINES, constant_model(0.40, 0.5)) \
            .extracted.amount_confidence == Decimal("0.4")


class TestFeatureVector:
    def test_이름_순서대로_편다(self):
        # 학습과 서빙이 같은 순서를 써야 한다 — 어긋나면 조용히 엉뚱한 확률이 나온다.
        feats = {name: float(i) for i, name in enumerate(AMOUNT_FEATURES)}
        assert vector(feats, AMOUNT_FEATURES) == [float(i) for i in range(len(AMOUNT_FEATURES))]

    def test_특징이_빠지면_터진다(self):
        with pytest.raises(KeyError):
            vector({"amount_score": 1.0}, AMOUNT_FEATURES)


class TestSweep:
    """임계는 정책 손잡이다 — 비대칭 비용의 최저점을 계산으로 찾는다."""

    def test_격자는_0과_1을_제외한다(self):
        from receipt_ocr.calib.sweep import default_grid

        grid = default_grid()
        assert Decimal("0") not in grid and Decimal("1") not in grid
        assert Decimal("0.80") in grid

    def test_최소비용_지점을_고른다(self):
        from receipt_ocr.calib.sweep import SweepPoint, best

        points = [
            SweepPoint(Decimal("0.5"), 40, 0.7, 0.2, 1),
            SweepPoint(Decimal("0.8"), 12, 0.8, 0.4, 0),
            SweepPoint(Decimal("0.9"), 30, 0.6, 0.7, 0),
        ]
        assert best(points).threshold == Decimal("0.8")

    def test_비용이_같으면_치명이_적은_쪽(self):
        # 비용이 같아도 오종결 1건과 리뷰 25건은 성격이 다르다.
        from receipt_ocr.calib.sweep import SweepPoint, best

        points = [
            SweepPoint(Decimal("0.5"), 25, 0.8, 0.0, 1),
            SweepPoint(Decimal("0.7"), 25, 0.8, 0.5, 0),
        ]
        assert best(points).threshold == Decimal("0.7")

    def test_빈_탐색은_거부한다(self):
        from receipt_ocr.calib.sweep import best

        with pytest.raises(ValueError):
            best([])


class TestPipelineGuards:
    """절차를 잔소리가 아니라 명령어 구조로 못박았는지."""

    def test_홀드아웃으로는_학습할_수_없다(self, tmp_path):
        # 홀드아웃에서 학습하거나 임계를 고르면 그 셋의 점수는 일반화 추정치가 아니게 된다.
        from receipt_ocr.calib import cli as calib_cli

        args = types.SimpleNamespace(dataset="data/goldenset.json", regularization="0.3", step="0.05")
        with pytest.raises(SystemExit) as exc:
            calib_cli.cmd_fit(args)
        assert "홀드아웃" in str(exc.value)

    def test_학습셋과_홀드아웃은_캐시를_따로_쓴다(self):
        # 섞이면 어느 셋을 읽은 판독인지 알 수 없다.
        from receipt_ocr.calib.cli import _cache_dir

        train = _cache_dir(pathlib.Path("data/trainset.json"))
        holdout = _cache_dir(pathlib.Path("data/goldenset.json"))
        assert train != holdout
        assert holdout.name == "holdout"

    def test_정책_파일이_없으면_운영_기본값을_쓴다(self, monkeypatch, tmp_path):
        from receipt_ocr.calib import cli as calib_cli

        monkeypatch.setattr(calib_cli, "POLICY_PATH", tmp_path / "없음.json")
        assert calib_cli._policy_threshold() == Decimal("0.80")
