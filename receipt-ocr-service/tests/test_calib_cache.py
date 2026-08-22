"""OCR 캐시 테스트.

캐시가 조용히 낡으면 **옛 이미지의 판독으로 새 정답을 채점**하게 된다 — 그러면 숫자는 나오는데
전부 거짓이다. 그래서 "언제 캐시를 무효로 보는가" 를 값 단위로 못박는다.
"""

from __future__ import annotations

import io
import json
import pathlib

import pytest

from receipt_ocr.calib import cache
from receipt_ocr.providers.parsing import OcrLine


def box(top: float, height: float) -> list[list[float]]:
    return [[0, top], [100, top], [100, top + height], [0, top + height]]


class FakeEngine:
    """RapidOCR 대체 — 호출을 세고 고정 판독을 돌려준다."""

    def __init__(self):
        self.calls = 0

    def __call__(self, data):
        self.calls += 1
        return [[box(10, 20), "64,100", 0.88]], 1.0


def png_bytes() -> bytes:
    from PIL import Image

    buffer = io.BytesIO()
    Image.new("RGB", (8, 8), (255, 255, 255)).save(buffer, format="PNG")
    return buffer.getvalue()


class StubCase:
    def __init__(self, case_id: str, image_path: str):
        self.case_id = case_id
        self.image_path = image_path


@pytest.fixture()
def image(tmp_path: pathlib.Path) -> pathlib.Path:
    path = tmp_path / "receipt.png"
    path.write_bytes(png_bytes())
    return path


class TestLoad:
    def test_파일이_없으면_None(self, tmp_path):
        assert cache.load(tmp_path, "C1") is None

    def test_깨진_JSON_은_None(self, tmp_path):
        (tmp_path / "C1.json").write_text("{not json", encoding="utf-8")
        assert cache.load(tmp_path, "C1") is None

    def test_스키마_버전이_다르면_무효로_본다(self, tmp_path):
        # 저장 형태가 바뀌었는데 옛 캐시를 읽으면 조용히 다른 것을 채점하게 된다.
        (tmp_path / "C1.json").write_text(
            json.dumps({"schema_version": cache.SCHEMA_VERSION + 1, "passes": {}}),
            encoding="utf-8",
        )
        assert cache.load(tmp_path, "C1") is None

    def test_두_패스를_그대로_복원한다(self, tmp_path):
        payload = {
            "schema_version": cache.SCHEMA_VERSION,
            "case_id": "C1",
            "passes": {
                name: [{"text": f"{name}-64,100", "confidence": 0.9, "height": 28.0, "top": 5.0}]
                for name in cache.PASSES
            },
        }
        (tmp_path / "C1.json").write_text(json.dumps(payload), encoding="utf-8")

        loaded = cache.load(tmp_path, "C1")

        assert set(loaded) == set(cache.PASSES)
        assert loaded["raw"][0] == OcrLine(text="raw-64,100", confidence=0.9, height=28.0, top=5.0)


class TestBuild:
    def test_이미지당_두_패스를_읽어_캐시한다(self, tmp_path, image):
        engine = FakeEngine()
        cases = [StubCase("C1", str(image))]

        read = cache.build(cases, tmp_path / "cache", engine=engine, progress=False)

        assert read == 1
        assert engine.calls == 2, "원본·전처리 두 패스를 모두 읽어야 중재가 성립한다"
        cached = cache.load(tmp_path / "cache", "C1")
        assert cached["raw"][0].text == "64,100"
        assert cached["prep"][0].text == "64,100"

    def test_이미_있는_건은_다시_읽지_않는다(self, tmp_path, image):
        engine = FakeEngine()
        cases = [StubCase("C1", str(image))]
        cache_dir = tmp_path / "cache"

        cache.build(cases, cache_dir, engine=engine, progress=False)
        again = cache.build(cases, cache_dir, engine=engine, progress=False)

        assert again == 0
        assert engine.calls == 2, "재실행이 OCR 을 다시 부르면 캐시의 존재 이유가 없다"

    def test_깨진_캐시는_다시_읽는다(self, tmp_path, image):
        engine = FakeEngine()
        cases = [StubCase("C1", str(image))]
        cache_dir = tmp_path / "cache"
        cache_dir.mkdir(parents=True)
        (cache_dir / "C1.json").write_text("{broken", encoding="utf-8")

        assert cache.build(cases, cache_dir, engine=engine, progress=False) == 1

    def test_이미지가_없으면_건너뛰고_알린다(self, tmp_path, capsys):
        engine = FakeEngine()
        cases = [StubCase("C1", str(tmp_path / "missing.png"))]

        read = cache.build(cases, tmp_path / "cache", engine=engine, progress=True)

        assert read == 0
        assert engine.calls == 0
        assert "이미지 없음" in capsys.readouterr().out

    def test_진행상황은_10건마다_보고한다(self, tmp_path, image, capsys):
        engine = FakeEngine()
        cases = [StubCase(f"C{i}", str(image)) for i in range(12)]

        assert cache.build(cases, tmp_path / "cache", engine=engine, progress=True) == 12

        out = capsys.readouterr().out
        assert "[10/12] 캐시 적재 중" in out

    def test_엔진을_안_주면_지연_생성한다(self, tmp_path, image, monkeypatch):
        # 엔진 초기화가 무거워서 필요할 때만 만든다 — 주입이 있으면 아예 만들지 않는다.
        created = []

        class FakeModule:
            class RapidOCR:
                def __init__(self):
                    created.append(1)

                def __call__(self, data):
                    return [[box(10, 20), "64,100", 0.88]], 1.0

        monkeypatch.setitem(__import__("sys").modules, "rapidocr_onnxruntime", FakeModule)

        cache.build([StubCase("C1", str(image))], tmp_path / "cache", progress=False)

        assert created == [1]
