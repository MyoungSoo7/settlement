"""자체 호스팅 OCR 프로바이더 — RapidOCR(ONNX Runtime, CPU) + 도메인 파서.

외부 호출이 없다. 영수증 이미지가 서비스 밖으로 나가지 않고, 가용성이 남의 손에 있지 않다
(baseline 실측에서 추출 실패 8.6% 가 전부 벤더측 503 이었다).

**한글은 못 읽어도 된다.** 기본 인식 모델은 한국어가 아니라서 상호명이 한자로 오독되지만,
``ExpenseReceiptMatcher`` 가 판정에 쓰는 필드는 총액과 거래일 **둘 다 숫자**이고 상호명은
"가맹점 등록명과 상시 불일치한다" 는 이유로 판정에서 빠져 있다. 그래서 숫자만 정확하면 된다.
(한국어 인식 모델로 교체하면 상호명까지 살릴 수 있지만, 대사 판정은 그것 없이도 성립한다.)

엔진은 무겁게 초기화되므로 프로바이더 인스턴스당 한 번만 만든다.
"""

from __future__ import annotations

import io
import time
from decimal import Decimal

from .base import ExtractionResult
from .parsing import OcrLine, ParseFailed, ParsedReceipt, choose_pass, parse_receipt


def _autocontrast(content: bytes) -> bytes:
    """대비 정규화 — 감열지 퇴색·저조도에서 글자를 살린다."""
    from PIL import Image, ImageOps

    with Image.open(io.BytesIO(content)) as image:
        grayscale = ImageOps.autocontrast(image.convert("L"), cutoff=1)
        buffer = io.BytesIO()
        grayscale.convert("RGB").save(buffer, format="PNG")
        return buffer.getvalue()


def _to_float(value) -> float:
    """RapidOCR 은 점수를 float 로도 str 로도 준다 — 버전마다 다르다."""
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def to_lines(raw_result) -> list[OcrLine]:
    """RapidOCR 출력(``[[box, text, score], ...]``)을 파서 입력으로 옮긴다.

    ``box`` 는 네 꼭짓점이다. 높이는 합계 줄을 찾는 단서라 여기서 계산해 넘긴다.
    """
    lines: list[OcrLine] = []
    for item in raw_result or []:
        if len(item) < 3:
            continue
        box, text, score = item[0], item[1], item[2]
        ys = [point[1] for point in box]
        lines.append(
            OcrLine(
                text=str(text),
                confidence=_to_float(score),
                height=float(max(ys) - min(ys)),
                top=float(min(ys)),
            )
        )
    return lines


class RapidOcrProvider:
    """이미지 → OCR → 도메인 파서 → 영수증 필드.

    :param engine: 테스트·교체용 주입 지점. 없으면 RapidOCR 을 지연 생성한다.
    :param preprocess: 대비 정규화를 먼저 걸지 여부. 저조도·퇴색 영수증에서 효과를 보려는
        것이지만, **켠 채로 baseline 과 비교하면 무엇이 기여했는지 알 수 없다** — 먼저 끄고
        재고, 그다음 켜서 차이를 본다.
    :param calibration: 신뢰도 교정 모델(선택). 주면 원점수 대신 "이 판독이 맞을 확률" 을 싣는다.
        **없으면 원점수로 돈다** — 모델 파일 하나 없다고 추출이 멈추면 안 된다.
    :param multipass: 원본과 전처리본을 **둘 다** 읽고 구조 검증으로 중재한다
        (:func:`~receipt_ocr.providers.parsing.choose_pass`). 켜면 ``preprocess`` 는 무시된다.
        비용은 이미지당 OCR 2회다.
    """

    def __init__(self, engine=None, *, preprocess: bool = False, multipass: bool = False,
                 calibration=None, label: str = "rapidocr"):
        self._engine = engine
        self._preprocess = preprocess
        self._multipass = multipass
        self._calibration = calibration
        self._label = label

    @property
    def name(self) -> str:
        suffix = "+multipass" if self._multipass else ("+prep" if self._preprocess else "")
        if self._calibration is not None:
            suffix += "+calib"
        return f"{self._label}{suffix}"

    @property
    def configured(self) -> bool:
        return True

    def _ensure_engine(self):
        if self._engine is None:
            from rapidocr_onnxruntime import RapidOCR  # 무거운 import — 필요할 때만

            self._engine = RapidOCR()
        return self._engine

    def _prepare(self, content: bytes) -> bytes:
        """대비 정규화 — 감열지 퇴색·저조도에서 글자를 살리려는 전처리."""
        return _autocontrast(content) if self._preprocess else content

    def _read(self, image: bytes) -> list[OcrLine]:
        raw, _ = self._ensure_engine()(image)
        return to_lines(raw)

    def parse(self, content: bytes) -> ParsedReceipt:
        """추출 상세(필드별 신뢰도 포함)까지 돌려준다 — 분석용 진입점."""
        if not self._multipass:
            lines = self._read(self._prepare(content))
            return self._calibrated(parse_receipt(lines), lines)

        # 한 패스가 실패해도 다른 패스가 살아 있으면 추출은 성립한다 — 개별 실패를 삼키고
        # 중재로 넘긴다. 둘 다 실패하면 choose_pass 가 끊는다.
        passes: list[tuple[ParsedReceipt, list[OcrLine]]] = []
        for image in (content, _autocontrast(content)):
            lines = self._read(image)
            try:
                passes.append((parse_receipt(lines), lines))
            except ParseFailed:
                continue
        chosen = choose_pass([p for p, _ in passes])
        # 특징은 채택된 판독을 만든 줄들에서 뽑는다 — 다른 패스의 줄로 교정하면 엉뚱한 확률이 나온다.
        lines = next(
            (ln for p, ln in passes
             if p.amount_source == chosen.amount_source and p.amount_method == chosen.amount_method),
            passes[0][1],
        )
        return self._calibrated(chosen, lines)

    def _calibrated(self, parsed: ParsedReceipt, lines: list[OcrLine]) -> ParsedReceipt:
        if self._calibration is None:
            return parsed
        from ..calib.apply import calibrate  # 교정은 선택 사항 — 없을 때 import 도 하지 않는다

        return calibrate(parsed, lines, self._calibration)

    def extract(self, content: bytes, content_type: str) -> ExtractionResult:
        started = time.perf_counter()
        try:
            parsed = self.parse(content)
        except ParseFailed as exc:
            # 무폴백 — 총액을 못 읽으면 지어내지 않고 실패로 센다(운영의 503).
            return ExtractionResult(
                None, (time.perf_counter() - started) * 1000, error=f"ParseFailed: {exc}"
            )
        except Exception as exc:  # 엔진 자체가 뻗은 경우도 한 건의 실패로 가둔다
            return ExtractionResult(
                None, (time.perf_counter() - started) * 1000,
                error=f"{type(exc).__name__}: {exc}",
            )

        return ExtractionResult(
            extracted=parsed.extracted,
            latency_ms=(time.perf_counter() - started) * 1000,
            raw=(
                f"amount={parsed.amount_source}({parsed.amount_confidence:.2f},"
                f"{parsed.amount_method}) date={parsed.date_source}"
                f"({parsed.date_confidence if parsed.date_confidence is None else round(parsed.date_confidence, 2)})"
            ),
            cost_usd=Decimal("0"),  # 자체 호스팅 — 건당 외부 과금이 없다
        )
