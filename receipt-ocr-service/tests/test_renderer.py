"""렌더러 테스트.

이미지의 '보기 좋음' 은 테스트할 수 없지만, **골든셋을 조용히 망가뜨리는 실패**는 테스트할 수 있다:
폰트 누락(두부 글자), 조건별 열화가 실제로 걸리지 않음, 같은 시드인데 다른 그림, 그리고
거래일 미인쇄 시나리오에서 날짜 줄이 그대로 찍히는 것(라벨과 그림이 어긋난다).
"""

from __future__ import annotations

from dataclasses import replace

import pytest
from PIL import Image, ImageStat

from receipt_ocr.synth import renderer
from receipt_ocr.synth.generator import RenderCondition, Scenario, generate
from receipt_ocr.synth.renderer import FontNotFoundError, render, render_to_file, resolve_font_path


def one(scenario: Scenario, condition: RenderCondition):
    """지정한 시나리오·조건의 영수증 1건을 만든다."""
    for receipt in generate(60, scenarios=[scenario]):
        if receipt.condition is condition:
            return receipt
    raise AssertionError(f"{scenario}/{condition} 조합을 생성하지 못했습니다")


def brightness(image: Image.Image) -> float:
    return ImageStat.Stat(image.convert("L")).mean[0]


def contrast(image: Image.Image) -> float:
    return ImageStat.Stat(image.convert("L")).stddev[0]


class TestFont:
    def test_한글_폰트를_찾는다(self):
        assert resolve_font_path()

    def test_폰트가_없으면_조용히_두부를_그리지_않고_끊는다(self, monkeypatch):
        # 폰트 없이 렌더하면 글자가 전부 □ 로 나오고, 그 골든셋으로 잰 점수는 전부 거짓이 된다.
        monkeypatch.setattr(renderer, "FONT_CANDIDATES", ["/없는/경로/font.ttf"])
        with pytest.raises(FontNotFoundError):
            resolve_font_path()


class TestRenderAllConditions:
    @pytest.mark.parametrize("condition", list(RenderCondition))
    def test_모든_조건이_렌더된다(self, condition):
        image = render(one(Scenario.CLEAN, condition), seed=7)
        assert image.mode == "RGB"
        assert image.width >= renderer.PAPER_WIDTH - 1
        assert image.height > 400

    def test_같은_시드는_같은_그림을_만든다(self):
        receipt = one(Scenario.CLEAN, RenderCondition.LOW_LIGHT)
        assert render(receipt, seed=11).tobytes() == render(receipt, seed=11).tobytes()

    def test_다른_시드는_다른_그림을_만든다(self):
        receipt = one(Scenario.CLEAN, RenderCondition.LOW_LIGHT)
        assert render(receipt, seed=11).tobytes() != render(receipt, seed=12).tobytes()


class TestDegradationActuallyApplies:
    """열화가 이름값을 하는지 — 조건만 붙고 그림이 그대로면 난이도 축이 죽은 것이다."""

    def test_퇴색은_대비를_낮춘다(self):
        pristine = render(one(Scenario.CLEAN, RenderCondition.PRISTINE), seed=3)
        faded = render(one(Scenario.CLEAN, RenderCondition.FADED), seed=3)
        assert contrast(faded) < contrast(pristine)

    def test_저조도는_더_어둡다(self):
        pristine = render(one(Scenario.CLEAN, RenderCondition.PRISTINE), seed=3)
        dark = render(one(Scenario.CLEAN, RenderCondition.LOW_LIGHT), seed=3)
        assert brightness(dark) < brightness(pristine)

    def test_기울임은_종이_밖_배경을_만든다(self):
        # 회전하면 캔버스가 종이보다 커진다 — 재단 경계가 사라져 검출 난이도가 올라간다.
        skewed = render(one(Scenario.CLEAN, RenderCondition.SKEWED), seed=3)
        assert skewed.width > renderer.PAPER_WIDTH


class TestLabelImageAgreement:
    def test_거래일_미인쇄_시나리오는_날짜_줄을_그리지_않는다(self):
        # 라벨은 '날짜 없음' 인데 그림에 날짜가 찍혀 있으면 정답이 거짓말이 된다.
        # 같은 영수증에서 날짜만 뺀다 — 다른 영수증끼리 비교하면 품목 수 차이로 우연히 통과한다.
        receipt = one(Scenario.CLEAN, RenderCondition.PRISTINE)
        dated = render(receipt, seed=5)
        undated = render(replace(receipt, printed_datetime=None), seed=5)
        assert undated.height < dated.height

    def test_실제로_한_줄_분량만_짧아진다(self):
        # 날짜 줄 하나만 빠져야 한다 — 두 줄 이상 차이나면 다른 것도 같이 사라진 것이다.
        receipt = one(Scenario.CLEAN, RenderCondition.PRISTINE)
        gap = render(receipt, seed=5).height - render(
            replace(receipt, printed_datetime=None), seed=5
        ).height
        assert 15 <= gap <= 40, f"한 줄 분량이 아닙니다: {gap}px"


class TestFileOutput:
    def test_JPEG_로_저장된다(self, tmp_path):
        receipt = one(Scenario.CLEAN, RenderCondition.PRISTINE)
        path = render_to_file(receipt, tmp_path)
        assert path.exists() and path.suffix == ".jpg"
        assert path.stat().st_size > 5_000
        with Image.open(path) as saved:
            assert saved.format == "JPEG"

    def test_파일명은_case_id_다(self, tmp_path):
        receipt = one(Scenario.CLEAN, RenderCondition.PRISTINE)
        assert render_to_file(receipt, tmp_path).stem == receipt.case_id
