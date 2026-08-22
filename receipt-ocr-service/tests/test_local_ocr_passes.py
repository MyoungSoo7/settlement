"""자체 호스팅 OCR — 전처리·다중 패스·교정 경로 테스트.

이 세 손잡이는 서로 얽혀 있다(다중 패스를 켜면 전처리 플래그는 무시된다). 어느 조합에서
무엇이 몇 번 읽히는지가 곧 비용이므로, 조합별 동작을 값으로 못박는다.
"""

from __future__ import annotations

import io
import math
from decimal import Decimal

import pytest

from receipt_ocr.calib.features import AMOUNT_FEATURES, DATE_FEATURES
from receipt_ocr.calib.model import CalibrationModel, Head
from receipt_ocr.providers.local_ocr import RapidOcrProvider


def box(top: float, height: float):
    return [[0, top], [100, top], [100, top + height], [0, top + height]]


#: 구조 검증이 서는 판독 — 58,273 + 5,827 = 64,100
STRUCTURAL_RESULT = [
    [box(200, 21), "2026-03-09 19:17", 0.82],
    [box(420, 21), "58,273", 0.79],
    [box(450, 23), "5,827", 0.80],
    [box(500, 28), "64,100", 0.73],
]
#: 금액을 하나 잘못 읽어 구조가 무너진 판독
BROKEN_RESULT = [
    [box(420, 21), "58,273", 0.95],
    [box(450, 23), "5,821", 0.95],
    [box(500, 28), "64,100", 0.95],
]
UNPARSEABLE_RESULT = [[box(100, 20), "감사합니다", 0.95]]


class ScriptedEngine:
    """호출 순서대로 다른 결과를 돌려주는 엔진 — 두 패스를 구분해 검증하기 위한 것."""

    def __init__(self, *results):
        self.results = list(results)
        self.calls: list[bytes] = []

    def __call__(self, content):
        self.calls.append(content)
        result = self.results[min(len(self.calls) - 1, len(self.results) - 1)]
        return result, [0.1]


def png_bytes(color=(255, 255, 255)) -> bytes:
    from PIL import Image

    buffer = io.BytesIO()
    Image.new("RGB", (16, 16), color).save(buffer, format="PNG")
    return buffer.getvalue()


def faded_png() -> bytes:
    """대비가 좁은 이미지 — 감열지 퇴색을 흉내 낸다.

    단색 이미지는 대비 정규화를 걸어도 픽셀이 그대로라 바이트가 안 변한다. 전처리가 실제로
    걸렸는지 보려면 명암 폭이 있어야 한다.
    """
    from PIL import Image

    image = Image.new("L", (16, 16))
    image.putdata([100 + (x % 8) for _ in range(16) for x in range(16)])
    buffer = io.BytesIO()
    image.convert("RGB").save(buffer, format="PNG")
    return buffer.getvalue()


def constant_model(probability: float) -> CalibrationModel:
    bias = math.log(probability / (1 - probability))
    return CalibrationModel(
        amount=Head(AMOUNT_FEATURES, (0.0,) * len(AMOUNT_FEATURES), bias),
        date=Head(DATE_FEATURES, (0.0,) * len(DATE_FEATURES), bias),
    )


class TestName:
    @pytest.mark.parametrize(
        "kwargs,expected",
        [
            ({}, "rapidocr"),
            ({"preprocess": True}, "rapidocr+prep"),
            ({"multipass": True}, "rapidocr+multipass"),
            # 다중 패스는 전처리를 포함하므로 이름에 둘 다 붙지 않는다.
            ({"multipass": True, "preprocess": True}, "rapidocr+multipass"),
        ],
    )
    def test_설정이_이름에_드러난다(self, kwargs, expected):
        # 이름이 곧 리포트의 식별자다 — 설정이 안 드러나면 두 실행을 구분할 수 없다.
        assert RapidOcrProvider(engine=ScriptedEngine(STRUCTURAL_RESULT), **kwargs).name == expected

    def test_교정_적용_여부도_이름에_드러난다(self):
        provider = RapidOcrProvider(engine=ScriptedEngine(STRUCTURAL_RESULT),
                                    calibration=constant_model(0.9))
        assert provider.name == "rapidocr+calib"

    def test_라벨을_바꿀_수_있다(self):
        provider = RapidOcrProvider(engine=ScriptedEngine(STRUCTURAL_RESULT), label="local")
        assert provider.name == "local"

    def test_외부_설정이_필요_없다(self):
        # 자체 호스팅이라 키·엔드포인트가 없다 — 언제나 설정 완료 상태다.
        assert RapidOcrProvider(engine=ScriptedEngine(STRUCTURAL_RESULT)).configured is True


class TestEnsureEngine:
    def test_엔진을_안_주면_지연_생성하고_한_번만_만든다(self, monkeypatch):
        created = []

        class FakeModule:
            class RapidOCR:
                def __init__(self):
                    created.append(1)

                def __call__(self, content):
                    return STRUCTURAL_RESULT, [0.1]

        monkeypatch.setitem(__import__("sys").modules, "rapidocr_onnxruntime", FakeModule)
        provider = RapidOcrProvider()

        assert created == [], "생성자에서 엔진을 만들면 import 만으로 무거워진다"
        provider.extract(png_bytes(), "image/png")
        provider.extract(png_bytes(), "image/png")

        assert created == [1], "엔진은 인스턴스당 한 번만 만들어야 한다"


class TestPreprocess:
    def test_전처리를_켜면_원본이_아니라_변환본을_읽는다(self):
        engine = ScriptedEngine(STRUCTURAL_RESULT)
        original = faded_png()

        RapidOcrProvider(engine=engine, preprocess=True).extract(original, "image/png")

        assert len(engine.calls) == 1
        assert engine.calls[0] != original, "전처리가 실제로 걸리지 않았다"

    def test_전처리를_끄면_원본_그대로_읽는다(self):
        engine = ScriptedEngine(STRUCTURAL_RESULT)
        original = faded_png()

        RapidOcrProvider(engine=engine).extract(original, "image/png")

        assert engine.calls == [original]


class TestMultipass:
    def test_두_패스를_모두_읽는다(self):
        engine = ScriptedEngine(STRUCTURAL_RESULT)
        original = faded_png()

        RapidOcrProvider(engine=engine, multipass=True).extract(original, "image/png")

        assert len(engine.calls) == 2, "다중 패스의 비용은 이미지당 OCR 2회다"
        assert engine.calls[0] == original
        assert engine.calls[1] != original

    def test_구조가_서는_패스를_채택한다(self):
        # 두 번째(전처리) 패스에서만 구조가 선다 — 점수가 낮아도 그쪽이 채택돼야 한다.
        engine = ScriptedEngine(BROKEN_RESULT, STRUCTURAL_RESULT)

        parsed = RapidOcrProvider(engine=engine, multipass=True).parse(faded_png())

        assert parsed.amount_method == "structural"
        assert parsed.extracted.total_amount == Decimal("64100")

    def test_한_패스가_실패해도_다른_패스로_성립한다(self):
        engine = ScriptedEngine(UNPARSEABLE_RESULT, STRUCTURAL_RESULT)

        result = RapidOcrProvider(engine=engine, multipass=True).extract(
            faded_png(), "image/png"
        )

        assert result.ok
        assert result.extracted.total_amount == Decimal("64100")

    def test_둘_다_실패하면_지어내지_않고_실패로_센다(self):
        engine = ScriptedEngine(UNPARSEABLE_RESULT)

        result = RapidOcrProvider(engine=engine, multipass=True).extract(
            faded_png(), "image/png"
        )

        assert not result.ok
        assert "ParseFailed" in result.error


class TestCalibration:
    def test_모델이_없으면_원점수를_그대로_쓴다(self):
        engine = ScriptedEngine(STRUCTURAL_RESULT)
        raw = RapidOcrProvider(engine=engine).parse(png_bytes())

        assert raw.extracted.amount_confidence != Decimal("0.9")

    def test_모델이_있으면_신뢰도가_교정_확률로_바뀐다(self):
        engine = ScriptedEngine(STRUCTURAL_RESULT)
        provider = RapidOcrProvider(engine=engine, calibration=constant_model(0.9))

        parsed = provider.parse(png_bytes())

        assert parsed.extracted.amount_confidence == Decimal("0.9")
        assert parsed.extracted.date_confidence == Decimal("0.9")

    def test_다중_패스에도_교정이_걸린다(self):
        engine = ScriptedEngine(BROKEN_RESULT, STRUCTURAL_RESULT)
        provider = RapidOcrProvider(engine=engine, multipass=True,
                                    calibration=constant_model(0.75))

        parsed = provider.parse(faded_png())

        assert parsed.extracted.amount_confidence == Decimal("0.75")


class TestExtract:
    def test_엔진이_뻗어도_한_건의_실패로_가둔다(self):
        class ExplodingEngine:
            def __call__(self, content):
                raise RuntimeError("onnx session crashed")

        result = RapidOcrProvider(engine=ExplodingEngine()).extract(png_bytes(), "image/png")

        assert not result.ok
        assert result.error == "RuntimeError: onnx session crashed"
        assert result.latency_ms >= 0

    def test_성공하면_판독_근거를_원문으로_남긴다(self):
        # "왜 그 값이었나" 를 사후에 답할 수 있어야 한다.
        result = RapidOcrProvider(engine=ScriptedEngine(STRUCTURAL_RESULT)).extract(
            png_bytes(), "image/png"
        )

        assert result.ok
        assert "amount=" in result.raw and "structural" in result.raw
        assert "date=" in result.raw
        assert result.cost_usd == Decimal("0"), "자체 호스팅은 건당 외부 과금이 없다"

    def test_거래일을_못_읽으면_원문에_None_이_남는다(self):
        result = RapidOcrProvider(engine=ScriptedEngine(BROKEN_RESULT)).extract(
            png_bytes(), "image/png"
        )

        assert result.ok
        assert "date=None(None)" in result.raw
