"""자체 호스팅 OCR 프로바이더 테스트 — 엔진은 가짜로 주입해 모델 없이 돈다."""

from __future__ import annotations

import datetime as _dt
from decimal import Decimal

import pytest

from receipt_ocr.providers.local_ocr import RapidOcrProvider, to_lines


def box(top: float, height: float):
    return [[0, top], [100, top], [100, top + height], [0, top + height]]


class FakeEngine:
    """RapidOCR 호출 규약(``(result, elapse)``)만 흉내 낸다."""

    def __init__(self, result, raises: Exception | None = None):
        self.result = result
        self.raises = raises
        self.calls: list[bytes] = []

    def __call__(self, content):
        self.calls.append(content)
        if self.raises:
            raise self.raises
        return self.result, [0.1, 0.1, 0.1]


RECEIPT_RESULT = [
    [box(200, 21), "2H :2026-03-09 19:17:26", 0.82],
    [box(420, 21), "58,273", 0.79],
    [box(450, 23), "5,827", 0.80],
    [box(500, 28), "64,100别", 0.73],
]


class TestToLines:
    def test_박스에서_높이와_위치를_계산한다(self):
        lines = to_lines([[box(100, 25), "합계", 0.9]])
        assert lines[0].height == pytest.approx(25)
        assert lines[0].top == pytest.approx(100)

    def test_점수가_문자열이어도_읽는다(self):
        # RapidOCR 은 버전에 따라 점수를 str 로 준다.
        assert to_lines([[box(0, 10), "x", "0.77"]])[0].confidence == pytest.approx(0.77)

    def test_점수가_망가지면_0으로_본다(self):
        assert to_lines([[box(0, 10), "x", None]])[0].confidence == pytest.approx(0.0)

    @pytest.mark.parametrize("raw", [None, [], [["짧은", "항목"]]])
    def test_비었거나_모양이_다른_출력은_건너뛴다(self, raw):
        assert to_lines(raw) == []


class TestExtract:
    def test_영수증을_추출한다(self):
        provider = RapidOcrProvider(engine=FakeEngine(RECEIPT_RESULT))
        result = provider.extract(b"image-bytes", "image/jpeg")
        assert result.ok
        assert result.extracted.total_amount == Decimal("64100")
        assert result.extracted.transaction_date == _dt.date(2026, 3, 9)
        assert result.latency_ms > 0

    def test_외부_과금이_없다(self):
        # 자체 호스팅의 논거 중 하나다 — 건당 비용이 0 이어야 한다.
        provider = RapidOcrProvider(engine=FakeEngine(RECEIPT_RESULT))
        assert provider.extract(b"x", "image/jpeg").cost_usd == Decimal("0")

    def test_판독_근거를_raw_에_남긴다(self):
        # 왜 그 값을 총액으로 골랐는지 사후에 볼 수 있어야 한다.
        result = RapidOcrProvider(engine=FakeEngine(RECEIPT_RESULT)).extract(b"x", "image/jpeg")
        assert "64,100" in result.raw and "structural" in result.raw

    def test_총액을_못_읽으면_실패_결과다(self):
        engine = FakeEngine([[box(0, 20), "감사합니다", 0.9]])
        result = RapidOcrProvider(engine=engine).extract(b"x", "image/jpeg")
        assert not result.ok and "ParseFailed" in result.error

    def test_엔진이_뻗어도_예외가_아니라_실패_결과다(self):
        # 한 건의 실패로 전체 평가를 잃으면 안 된다.
        engine = FakeEngine(None, raises=RuntimeError("onnx 세션 붕괴"))
        result = RapidOcrProvider(engine=engine).extract(b"x", "image/jpeg")
        assert not result.ok and "onnx 세션 붕괴" in result.error


class TestPreprocess:
    def test_전처리를_끄면_원본이_그대로_간다(self):
        engine = FakeEngine(RECEIPT_RESULT)
        RapidOcrProvider(engine=engine, preprocess=False).extract(b"raw-bytes", "image/jpeg")
        assert engine.calls == [b"raw-bytes"]

    def test_전처리를_켜면_이미지가_바뀌어_간다(self, tmp_path):
        from PIL import Image

        path = tmp_path / "x.png"
        # 단색이면 autocontrast 가 아무것도 바꾸지 않는다 — 대비가 좁은 두 톤을 넣는다.
        image = Image.new("L", (40, 20), color=110)
        image.paste(140, (0, 0, 40, 10))
        image.convert("RGB").save(path)
        engine = FakeEngine(RECEIPT_RESULT)
        RapidOcrProvider(engine=engine, preprocess=True).extract(path.read_bytes(), "image/png")
        assert engine.calls and engine.calls[0] != path.read_bytes()

    def test_이름에_전처리_여부가_드러난다(self):
        # 리포트만 보고 어떤 구성이었는지 알 수 있어야 비교가 성립한다.
        assert RapidOcrProvider(engine=FakeEngine([]), preprocess=True).name.endswith("+prep")
        assert not RapidOcrProvider(engine=FakeEngine([]), preprocess=False).name.endswith("+prep")
