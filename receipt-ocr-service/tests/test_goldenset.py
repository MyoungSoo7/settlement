"""골든셋 영속 테스트 — 비교의 기준선이 되는 파일이라 왕복이 정확해야 한다."""

from __future__ import annotations

import datetime as _dt
import json
import pathlib
from decimal import Decimal

import pytest

from receipt_ocr.eval import goldenset
from receipt_ocr.eval.goldenset import to_golden_case
from receipt_ocr.synth.generator import generate


def sample_cases():
    return [to_golden_case(r, image_path=f"build/images/{r.case_id}.jpg") for r in generate(6)]


class TestRoundTrip:
    def test_저장하고_읽으면_같다(self, tmp_path):
        cases = sample_cases()
        path = tmp_path / "golden.json"
        goldenset.save(cases, path)
        assert goldenset.load(path) == cases

    def test_금액은_문자열로_직렬화된다(self, tmp_path):
        # JSON number 로 넣으면 float 이 되어 원 단위가 흔들린다.
        path = tmp_path / "golden.json"
        goldenset.save(sample_cases(), path)
        raw = json.loads(path.read_text(encoding="utf-8"))
        assert isinstance(raw["cases"][0]["truth_amount"], str)
        assert isinstance(raw["cases"][0]["capture"]["amount"], str)

    def test_시각의_타임존이_보존된다(self, tmp_path):
        # KST 오프셋이 날아가면 매입일 환산이 틀어져 라벨이 조용히 바뀐다.
        path = tmp_path / "golden.json"
        cases = sample_cases()
        goldenset.save(cases, path)
        loaded = goldenset.load(path)
        assert loaded[0].capture.captured_at.utcoffset() == _dt.timedelta(hours=9)

    def test_거래일_없음도_왕복한다(self, tmp_path):
        from dataclasses import replace

        path = tmp_path / "golden.json"
        cases = [replace(sample_cases()[0], truth_date=None)]
        goldenset.save(cases, path)
        assert goldenset.load(path)[0].truth_date is None


class TestSchemaVersion:
    def test_버전이_다르면_거부한다(self, tmp_path):
        # 조용히 다른 포맷을 읽어 비교를 망치느니 여기서 끊는다.
        path = tmp_path / "golden.json"
        path.write_text(json.dumps({"schema_version": 999, "cases": []}), encoding="utf-8")
        with pytest.raises(ValueError):
            goldenset.load(path)


class TestRelativeImagePaths:
    def test_상대경로로_저장된다(self, tmp_path):
        path = tmp_path / "golden.json"
        goldenset.save(sample_cases(), path)
        raw = json.loads(path.read_text(encoding="utf-8"))
        assert not pathlib.Path(raw["cases"][0]["image_path"]).is_absolute()

    def test_base_dir_로_절대경로가_된다(self, tmp_path):
        path = tmp_path / "golden.json"
        goldenset.save(sample_cases(), path)
        loaded = goldenset.load(path, base_dir=tmp_path)
        assert pathlib.Path(loaded[0].image_path).is_absolute()
        assert pathlib.Path(loaded[0].image_path).is_relative_to(tmp_path)

    def test_base_dir_없으면_그대로_둔다(self, tmp_path):
        path = tmp_path / "golden.json"
        goldenset.save(sample_cases(), path)
        assert not pathlib.Path(goldenset.load(path)[0].image_path).is_absolute()


class TestCommittedGoldenset:
    """저장소에 굳혀 둔 골든셋이 실제로 읽히는지 — 이게 비교의 기준선이다."""

    def test_기본_골든셋이_읽힌다(self):
        service_root = pathlib.Path(__file__).resolve().parent.parent
        path = service_root / "data" / "goldenset.json"
        if not path.exists():
            pytest.skip("골든셋 미생성 — `python -m receipt_ocr build` 로 만든다")
        cases = goldenset.load(path, base_dir=service_root)
        assert cases
        missing = [c.case_id for c in cases if not pathlib.Path(c.image_path).exists()]
        assert not missing, f"이미지가 없습니다(러너에서 조용히 UNAVAILABLE 로 집계됨): {missing[:5]}"
        assert len({c.case_id for c in cases}) == len(cases)
