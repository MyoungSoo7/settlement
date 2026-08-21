"""합성 영수증 이미지 렌더러 — 감열지 전표 레이아웃 + 촬영/인쇄 열화.

깨끗한 전표만 만들면 모델이 쉬운 문제만 풀게 되고, 리뷰 큐 유입률 같은 지표가 실제보다 좋게
나온다. 그래서 **정답은 그대로 두고 난이도만 바꾸는** 열화를 조건별로 입힌다
(:class:`~receipt_ocr.synth.generator.RenderCondition`).

한글 폰트가 없으면 글자가 두부(□)로 렌더되어 **골든셋 전체가 조용히 쓰레기가 된다.** 그래서
폰트를 못 찾으면 예외로 세게 끊는다 — 이건 fail-fast 가 맞는 자리다.
"""

from __future__ import annotations

import os
import pathlib
import random
from decimal import Decimal

from PIL import Image, ImageChops, ImageDraw, ImageEnhance, ImageFilter, ImageFont

from .generator import RenderCondition, SyntheticReceipt

#: 80mm 감열지를 203dpi 로 뽑았을 때의 픽셀 폭.
PAPER_WIDTH = 576
MARGIN_X = 34
LINE_GAP = 8

#: 폰트 후보 — 환경변수 > 윈도우 > 리눅스(도커) 순. 도커 이미지는 fonts-nanum 을 깔아야 한다.
FONT_CANDIDATES = [
    os.environ.get("RECEIPT_FONT_PATH", ""),
    "C:/Windows/Fonts/malgun.ttf",
    "/usr/share/fonts/truetype/nanum/NanumGothic.ttf",
    "/usr/share/fonts/truetype/nanum/NanumBarunGothic.ttf",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
    "/System/Library/Fonts/AppleSDGothicNeo.ttc",
]


class FontNotFoundError(RuntimeError):
    """한글 폰트를 못 찾음 — 조용히 두부 글자를 렌더하느니 여기서 끊는다."""


def resolve_font_path() -> str:
    for candidate in FONT_CANDIDATES:
        if candidate and pathlib.Path(candidate).exists():
            return candidate
    raise FontNotFoundError(
        "한글 폰트를 찾지 못했습니다. RECEIPT_FONT_PATH 로 .ttf/.ttc 경로를 지정하거나 "
        "나눔고딕/Noto CJK 를 설치하세요. 폰트 없이 렌더하면 글자가 두부로 나와 골든셋이 무의미해집니다."
    )


def _fonts(scale: float = 1.0) -> dict[str, ImageFont.FreeTypeFont]:
    path = resolve_font_path()
    return {
        "title": ImageFont.truetype(path, int(30 * scale)),
        "body": ImageFont.truetype(path, int(20 * scale)),
        "small": ImageFont.truetype(path, int(17 * scale)),
        "total": ImageFont.truetype(path, int(26 * scale)),
    }


def _won(value: Decimal, suffix: bool = False) -> str:
    """원 단위 천 단위 구분 — 영수증은 항상 이렇게 찍는다."""
    text = f"{int(value):,}"
    return f"{text}원" if suffix else text


class _Canvas:
    """세로로 흘려 쓰는 단순 레이아웃기 — 영수증은 위에서 아래로만 흐른다."""

    def __init__(self, draw: ImageDraw.ImageDraw, fonts: dict, width: int):
        self.draw = draw
        self.fonts = fonts
        self.width = width
        self.y = 26

    def center(self, text: str, font_key: str = "body", gap: int = LINE_GAP) -> None:
        font = self.fonts[font_key]
        w = self.draw.textlength(text, font=font)
        self.draw.text(((self.width - w) / 2, self.y), text, font=font, fill=0)
        self.y += font.size + gap

    def left(self, text: str, font_key: str = "small", gap: int = LINE_GAP) -> None:
        font = self.fonts[font_key]
        self.draw.text((MARGIN_X, self.y), text, font=font, fill=0)
        self.y += font.size + gap

    def row(self, left: str, right: str, font_key: str = "small", gap: int = LINE_GAP) -> None:
        """좌측 라벨 + 우측 정렬 값 — 금액 줄은 전부 이 형태다."""
        font = self.fonts[font_key]
        self.draw.text((MARGIN_X, self.y), left, font=font, fill=0)
        w = self.draw.textlength(right, font=font)
        self.draw.text((self.width - MARGIN_X - w, self.y), right, font=font, fill=0)
        self.y += font.size + gap

    def rule(self, char: str = "-", gap: int = 10) -> None:
        font = self.fonts["small"]
        unit = max(1.0, self.draw.textlength(char, font=font))
        count = int((self.width - 2 * MARGIN_X) / unit)
        self.draw.text((MARGIN_X, self.y), char * count, font=font, fill=0)
        self.y += font.size + gap


def render(receipt: SyntheticReceipt, *, seed: int | None = None) -> Image.Image:
    """영수증 1장을 그린 뒤 조건별 열화를 입힌다. 반환은 RGB 이미지."""
    fonts = _fonts()
    # 높이는 넉넉히 잡고 마지막에 실제 내용 높이로 자른다.
    canvas_img = Image.new("L", (PAPER_WIDTH, 1600), color=255)
    draw = ImageDraw.Draw(canvas_img)
    canvas = _Canvas(draw, fonts, PAPER_WIDTH)

    canvas.center("신용카드 매출전표", "title", gap=16)
    canvas.rule()
    canvas.left(f"가맹점명 : {receipt.merchant_name}")
    canvas.left(f"사업자번호: {receipt.business_no}")
    canvas.left(f"대 표 자 : {receipt.owner}")
    canvas.left(f"주    소 : {receipt.address}")
    canvas.left(f"전    화 : {receipt.phone}")
    canvas.rule()

    if receipt.printed_datetime is not None:
        canvas.left(f"거래일시 : {receipt.printed_datetime.strftime(receipt.date_format)}")
    canvas.left("거래유형 : 신용승인")
    canvas.left(f"카드번호 : {receipt.card_masked}")
    canvas.left("할부개월 : 일시불")
    canvas.left(f"승인번호 : {receipt.approval_no}")
    canvas.rule()

    canvas.row("품목", "수량      금액")
    for item in receipt.items:
        canvas.row(item.name, f"{item.quantity}   {_won(item.amount)}")
    canvas.rule()

    if receipt.discount > 0:
        # 소계·할인이 함께 찍히면 금액 후보가 셋이 된다 — 모델이 합계를 골라야 한다.
        canvas.row("소    계", _won(receipt.subtotal, receipt.show_currency_suffix))
        canvas.row("할    인", "-" + _won(receipt.discount, receipt.show_currency_suffix))
    canvas.row("공급가액", _won(receipt.supply_amount, receipt.show_currency_suffix))
    canvas.row("부 가 세", _won(receipt.vat, receipt.show_currency_suffix))
    canvas.rule("=")
    canvas.row("합    계", _won(receipt.printed_total, receipt.show_currency_suffix), "total", gap=12)
    canvas.rule()
    canvas.center("이용해 주셔서 감사합니다", "small", gap=6)
    canvas.center("*** 고객용 ***", "small")

    cropped = canvas_img.crop((0, 0, PAPER_WIDTH, min(canvas.y + 26, canvas_img.height)))
    rng = random.Random(seed if seed is not None else hash(receipt.case_id) & 0xFFFFFFFF)
    return _degrade(cropped, receipt.condition, rng)


# --------------------------------------------------------------------------------------
# 열화 — 정답은 건드리지 않고 난이도만 바꾼다
# --------------------------------------------------------------------------------------


def _shading_map(size: tuple[int, int], rng: random.Random, low: int, high: int) -> Image.Image:
    """저주파 명암 지도 — 작은 노이즈를 키워 부드러운 얼룩/구김 그림자를 만든다."""
    w, h = size
    small = Image.new("L", (10, max(2, h // 60)))
    small.putdata([rng.randrange(low, high) for _ in range(small.width * small.height)])
    return small.resize((w, h), Image.Resampling.BICUBIC)


def _add_noise(image: Image.Image, rng: random.Random, amount: int) -> Image.Image:
    """센서 노이즈 — 픽셀별 가감. 저조도 촬영에서 OCR 을 실제로 무너뜨리는 요인이다."""
    noise = Image.new("L", image.size)
    noise.putdata([128 + rng.randrange(-amount, amount + 1) for _ in range(image.width * image.height)])
    return ImageChops.add(ImageChops.subtract(image, noise, scale=1, offset=128), noise, scale=1, offset=-128)


def _on_desk(image: Image.Image, rng: random.Random, pad: int = 40) -> Image.Image:
    """책상 위에 놓고 찍은 느낌 — 종이 밖 배경을 만들어 재단 경계를 흐린다."""
    bg_tone = rng.randrange(150, 200)
    canvas = Image.new("L", (image.width + pad * 2, image.height + pad * 2), color=bg_tone)
    canvas.paste(image, (pad, pad))
    return canvas


def _degrade(image: Image.Image, condition: RenderCondition, rng: random.Random) -> Image.Image:
    if condition is RenderCondition.PRISTINE:
        # 스캐너로 뜬 깨끗한 전표 — 그래도 완전 무결점은 비현실적이라 아주 약한 노이즈만.
        result = _add_noise(image, rng, 4)

    elif condition is RenderCondition.FADED:
        # 감열지 퇴색 — 시간이 지나면 글자가 흐려진다. 대비를 크게 낮춘다.
        result = ImageEnhance.Contrast(image).enhance(0.42)
        result = ImageEnhance.Brightness(result).enhance(1.12)
        result = result.filter(ImageFilter.GaussianBlur(0.7))
        result = _add_noise(result, rng, 6)

    elif condition is RenderCondition.CRUMPLED:
        # 구겨서 주머니에 넣었다 편 영수증 — 접힌 자리마다 그림자가 진다.
        shading = _shading_map(image.size, rng, 165, 255)
        result = ImageChops.multiply(image, shading.point(lambda v: 128 + v // 2))
        result = _add_noise(result, rng, 7)
        result = result.filter(ImageFilter.GaussianBlur(0.4))

    elif condition is RenderCondition.SKEWED:
        # 손으로 든 채 비스듬히 촬영 — 회전 + 약한 기울임.
        placed = _on_desk(image, rng)
        shear = rng.uniform(-0.035, 0.035)
        placed = placed.transform(
            placed.size, Image.Transform.AFFINE, (1, shear, -shear * placed.height / 2, 0, 1, 0),
            resample=Image.Resampling.BICUBIC, fillcolor=180,
        )
        result = placed.rotate(rng.uniform(-5.0, 5.0), resample=Image.Resampling.BICUBIC,
                               expand=True, fillcolor=180)
        result = _add_noise(result, rng, 5)

    elif condition is RenderCondition.LOW_LIGHT:
        # 어두운 실내에서 촬영 — 어둡고 노이즈가 많고 한쪽으로 빛이 떨어진다.
        result = ImageEnhance.Brightness(image).enhance(0.52)
        gradient = _shading_map(image.size, rng, 120, 210)
        result = ImageChops.multiply(result, gradient.point(lambda v: 150 + v // 3))
        result = _add_noise(result, rng, 16)
        result = result.filter(ImageFilter.GaussianBlur(0.6))

    elif condition is RenderCondition.LOW_RES:
        # 멀리서 찍어 작게 잡힌 영수증 — 축소했다가 되키우면 글자 획이 뭉개진다.
        # 실제 업로드에서 가장 흔한 열화이면서, 작은 글씨(거래일시)부터 무너진다.
        scale = rng.uniform(0.28, 0.38)
        small = image.resize((max(1, int(image.width * scale)), max(1, int(image.height * scale))),
                             Image.Resampling.BILINEAR)
        result = small.resize(image.size, Image.Resampling.BICUBIC)
        result = _add_noise(result, rng, 8)

    elif condition is RenderCondition.GLARE:
        # 형광등·플래시 반사 — 가로 밴드 하나가 하얗게 날아간다. 어느 줄이 걸릴지는 운이다.
        band_top = rng.randrange(int(image.height * 0.25), int(image.height * 0.85))
        band_height = rng.randrange(int(image.height * 0.06), int(image.height * 0.14))
        glare = Image.new("L", image.size, color=0)
        ImageDraw.Draw(glare).rectangle(
            [0, band_top, image.width, band_top + band_height], fill=rng.randrange(150, 225)
        )
        glare = glare.filter(ImageFilter.GaussianBlur(image.height * 0.02))
        result = ImageChops.add(image, glare)
        result = _add_noise(result, rng, 6)

    else:  # pragma: no cover - Enum 이 닫혀 있어 도달 불가
        raise ValueError(f"알 수 없는 렌더 조건: {condition}")

    return result.convert("RGB")


def render_to_file(receipt: SyntheticReceipt, directory: pathlib.Path, *,
                   seed: int | None = None, quality: int = 88) -> pathlib.Path:
    """영수증을 JPEG 로 저장하고 경로를 돌려준다.

    JPEG 인 이유는 실제 업로드가 휴대폰 사진(JPEG)이기 때문이다 — 압축 아티팩트도 난이도의 일부다.
    """
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / f"{receipt.case_id}.jpg"
    render(receipt, seed=seed).save(path, format="JPEG", quality=quality)
    return path
