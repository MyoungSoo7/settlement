"""교정 파이프라인 CLI 테스트.

이 CLI 의 존재 이유는 **어긋난 사용을 명령어 구조로 막는 것**이다. 홀드아웃으로 학습하는 순간
이후 모든 수치가 일반화 추정치가 아니게 되는데, 그건 숫자만 봐서는 드러나지 않는다. 그래서
"거부해야 할 사용" 을 테스트로 못박는다.
"""

from __future__ import annotations

import datetime as _dt
import json
import pathlib
from decimal import Decimal

import pytest

from receipt_ocr.calib import cache, cli
from receipt_ocr.calib.model import CalibrationModel, Head
from receipt_ocr.calib.features import AMOUNT_FEATURES, DATE_FEATURES
from receipt_ocr.eval import goldenset
from receipt_ocr.eval.scorer import CaptureRef, GoldenCase

CAPTURED_AT = _dt.datetime(2026, 3, 9, 19, 17, tzinfo=_dt.timezone.utc)
TRUTH_DATE = _dt.date(2026, 3, 9)

STRUCTURAL = [
    {"text": "58,273", "confidence": 0.70, "height": 20.0, "top": 10.0},
    {"text": "5,827", "confidence": 0.70, "height": 20.0, "top": 30.0},
    {"text": "64,100", "confidence": 0.70, "height": 28.0, "top": 50.0},
]
WITH_DATE = STRUCTURAL + [{"text": "2026-03-09 19:17", "confidence": 0.55,
                           "height": 18.0, "top": 70.0}]
UNPARSEABLE = [{"text": "감사합니다", "confidence": 0.9, "height": 20.0, "top": 10.0}]


def write_cache(cache_dir: pathlib.Path, case_id: str, lines: list[dict]) -> None:
    cache_dir.mkdir(parents=True, exist_ok=True)
    (cache_dir / f"{case_id}.json").write_text(
        json.dumps({"schema_version": cache.SCHEMA_VERSION, "case_id": case_id,
                    "passes": {name: lines for name in cache.PASSES}}, ensure_ascii=False),
        encoding="utf-8",
    )


def golden(case_id: str, *, amount: str = "64100") -> GoldenCase:
    return GoldenCase(
        case_id=case_id,
        capture=CaptureRef(f"CAP-{case_id}", Decimal("64100"), CAPTURED_AT),
        truth_amount=Decimal(amount),
        truth_date=TRUTH_DATE,
        scenario="정상",
        condition="양호",
    )


def constant_model(probability: float = 0.9) -> CalibrationModel:
    import math

    bias = math.log(probability / (1 - probability))
    return CalibrationModel(
        amount=Head(AMOUNT_FEATURES, (0.0,) * len(AMOUNT_FEATURES), bias),
        date=Head(DATE_FEATURES, (0.0,) * len(DATE_FEATURES), bias),
    )


@pytest.fixture()
def workspace(tmp_path, monkeypatch):
    """SERVICE_ROOT·모델·정책 경로를 임시 디렉터리로 옮긴 작업 공간."""
    monkeypatch.setattr(cli, "SERVICE_ROOT", tmp_path)
    monkeypatch.setattr(cli, "MODEL_PATH", tmp_path / "data" / "calibration.json")
    monkeypatch.setattr(cli, "POLICY_PATH", tmp_path / "data" / "calibration-policy.json")
    return tmp_path


def write_dataset(root: pathlib.Path, name: str, cases: list[GoldenCase]) -> pathlib.Path:
    path = root / "data" / f"{name}.json"
    goldenset.save(cases, path)
    return path


class TestCacheDir:
    def test_학습셋과_홀드아웃의_캐시를_분리한다(self, workspace):
        # 섞이면 어느 셋을 읽은 판독인지 알 수 없게 된다.
        train_dir = cli._cache_dir(pathlib.Path("data/trainset.json"))
        holdout_dir = cli._cache_dir(pathlib.Path("data/goldenset.json"))

        assert train_dir != holdout_dir
        assert train_dir.name == "train"
        assert holdout_dir.name == "holdout"


class TestPredictions:
    def test_캐시가_없으면_실패_예측으로_남긴다(self, tmp_path):
        # 조용히 빼면 분모가 줄어 점수가 부풀려진다.
        preds = cli._predictions([golden("C1")], tmp_path, None)

        assert len(preds) == 1
        assert preds[0].extracted is None
        assert "캐시 없음" in preds[0].error

    def test_두_패스_모두_파싱_실패면_실패_예측이다(self, tmp_path):
        write_cache(tmp_path, "C1", UNPARSEABLE)

        preds = cli._predictions([golden("C1")], tmp_path, None)

        assert preds[0].extracted is None
        assert "ParseFailed" in preds[0].error

    def test_교정_없이도_예측을_만든다(self, tmp_path):
        write_cache(tmp_path, "C1", WITH_DATE)

        preds = cli._predictions([golden("C1")], tmp_path, None)

        assert preds[0].extracted.total_amount == Decimal("64100")

    def test_모델을_주면_신뢰도가_교정_확률로_바뀐다(self, tmp_path):
        write_cache(tmp_path, "C1", WITH_DATE)

        raw = cli._predictions([golden("C1")], tmp_path, None)[0]
        calibrated = cli._predictions([golden("C1")], tmp_path, constant_model(0.9))[0]

        assert calibrated.extracted.total_amount == raw.extracted.total_amount
        assert calibrated.extracted.amount_confidence == Decimal("0.9")


class TestCmdCache:
    def test_읽은_건수를_보고한다(self, workspace, monkeypatch, capsys):
        dataset = write_dataset(workspace, "trainset", [golden("C1"), golden("C2")])
        monkeypatch.setattr(cache, "build", lambda cases, directory, **kw: len(cases))

        args = type("Args", (), {"dataset": str(dataset)})()
        assert cli.cmd_cache(args) == 0

        assert "2건 중 2건 새로 읽음" in capsys.readouterr().out


class TestCmdFit:
    def _args(self, dataset: pathlib.Path, **overrides):
        base = {"dataset": str(dataset), "regularization": "0.3", "step": "0.25"}
        base.update(overrides)
        return type("Args", (), base)()

    def test_홀드아웃으로는_학습을_거부한다(self, workspace):
        dataset = write_dataset(workspace, "goldenset", [golden("C1")])

        with pytest.raises(SystemExit, match="홀드아웃"):
            cli.cmd_fit(self._args(dataset))

    def test_캐시가_없으면_먼저_캐시하라고_알린다(self, workspace):
        dataset = write_dataset(workspace, "trainset", [golden("C1")])

        with pytest.raises(SystemExit, match="표본이 없습니다"):
            cli.cmd_fit(self._args(dataset))

    def test_모델과_임계를_굳히고_탐색_결과를_보여준다(self, workspace, capsys):
        cases = [golden(f"C{i}") for i in range(4)]
        cases[3] = golden("C3", amount="99999")  # 오답 1건 — 두 클래스가 생긴다
        dataset = write_dataset(workspace, "trainset", cases)

        cache_dir = cli._cache_dir(dataset)
        for case in cases:
            write_cache(cache_dir, case.case_id, WITH_DATE)

        assert cli.cmd_fit(self._args(dataset)) == 0

        model = CalibrationModel.load(cli.MODEL_PATH)
        assert model is not None and model.amount.trained_on == 4

        policy = json.loads(cli.POLICY_PATH.read_text(encoding="utf-8"))
        assert Decimal(policy["review_threshold"]) > 0
        # 임계를 어느 셋에서 골랐는지 파일에 남는다 — 나중에 "홀드아웃이었나" 를 물을 수 있어야 한다.
        assert policy["chosen_on"] == "trainset.json"

        out = capsys.readouterr().out
        assert "표본 4건" in out
        assert "학습셋 기준 — 홀드아웃이 아니다" in out
        assert "선택된 임계" in out


class TestCmdHoldout:
    def _args(self, dataset: pathlib.Path, *, threshold: str = "", uncalibrated: bool = False):
        return type("Args", (), {"dataset": str(dataset), "threshold": threshold,
                                 "uncalibrated": uncalibrated})()

    def _prepare(self, workspace) -> pathlib.Path:
        cases = [golden(f"C{i}") for i in range(3)]
        dataset = write_dataset(workspace, "goldenset", cases)
        cache_dir = cli._cache_dir(dataset)
        for case in cases:
            write_cache(cache_dir, case.case_id, WITH_DATE)
        return dataset

    def test_교정_없이_대조군을_돌린다(self, workspace, capsys):
        dataset = self._prepare(workspace)

        assert cli.cmd_holdout(self._args(dataset, uncalibrated=True)) == 0

        out = capsys.readouterr().out
        assert "local+multipass @임계" in out
        assert "+calib" not in out

    def test_모델이_있으면_교정본으로_돌고_라벨에_드러난다(self, workspace, capsys):
        dataset = self._prepare(workspace)
        constant_model(0.9).save(cli.MODEL_PATH)

        assert cli.cmd_holdout(self._args(dataset)) == 0

        assert "local+multipass+calib" in capsys.readouterr().out

    def test_임계를_직접_주면_그걸_쓴다(self, workspace, capsys):
        dataset = self._prepare(workspace)

        cli.cmd_holdout(self._args(dataset, threshold="0.55", uncalibrated=True))

        assert "@임계 0.55" in capsys.readouterr().out

    def test_임계를_비우면_학습셋에서_고른_정책값을_쓴다(self, workspace, capsys):
        dataset = self._prepare(workspace)
        cli.POLICY_PATH.parent.mkdir(parents=True, exist_ok=True)
        cli.POLICY_PATH.write_text(json.dumps({"review_threshold": "0.35"}), encoding="utf-8")

        cli.cmd_holdout(self._args(dataset, uncalibrated=True))

        assert "@임계 0.35" in capsys.readouterr().out


class TestPolicyThreshold:
    def test_정책_파일이_없으면_운영_기본값(self, workspace):
        assert cli._policy_threshold() == Decimal("0.80")


class TestRegister:
    def test_세_단계가_같은_진입점에_등록된다(self):
        import argparse

        parser = argparse.ArgumentParser()
        cli.register(parser.add_subparsers(dest="command", required=True))

        for command in ("cache", "fit", "holdout"):
            args = parser.parse_args(["calibrate", command])
            assert args.calib_command == command
            assert callable(args.func)

    def test_하위_명령을_빠뜨리면_거부한다(self):
        import argparse

        parser = argparse.ArgumentParser()
        cli.register(parser.add_subparsers(dest="command", required=True))

        with pytest.raises(SystemExit):
            parser.parse_args(["calibrate"])
