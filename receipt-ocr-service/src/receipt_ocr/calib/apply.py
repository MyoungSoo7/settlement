"""교정 적용 — 원점수를 "맞을 확률"로 바꿔 끼운다.

원점수(OCR 토큰 점수 ± 구조 보너스/벌점)는 **운영 임계 0.80 과 스케일이 맞지 않는다.** 그래서
임계를 넘느냐로 판정하면 리뷰 큐가 넘치거나(과소평가) 오답이 종결된다(과신).

교정 모델은 판독 특징에서 P(정답)을 낸다. 이 값은 정의상 확률이라 임계와 같은 눈금 위에 있고,
"0.80 미만이면 리뷰" 라는 정책이 비로소 말이 된다.

**모델이 없으면 아무것도 하지 않는다.** 교정은 선택 사항이고, 없을 때 원점수로 도는 경로가
계속 살아 있어야 한다(모델 파일 하나 없다고 추출이 멈추면 안 된다).
"""

from __future__ import annotations

from dataclasses import replace

from ..domain.extracted import ExtractedReceipt
from ..providers.parsing import OcrLine, ParsedReceipt, to_confidence
from .features import amount_features, date_features
from .model import CalibrationModel


def calibrate(parsed: ParsedReceipt, lines: list[OcrLine],
              model: CalibrationModel | None) -> ParsedReceipt:
    """판독의 두 신뢰도를 교정 확률로 갈아 끼운다. ``model`` 이 None 이면 그대로 돌려준다."""
    if model is None:
        return parsed

    amount_probability = model.amount.probability(amount_features(parsed, lines))

    date_feats = date_features(parsed, lines)
    date_probability = None if date_feats is None else model.date.probability(date_feats)

    return replace(
        parsed,
        extracted=ExtractedReceipt(
            merchant_name=parsed.extracted.merchant_name,
            transaction_date=parsed.extracted.transaction_date,
            total_amount=parsed.extracted.total_amount,
            amount_confidence=to_confidence(amount_probability),
            date_confidence=None if date_probability is None else to_confidence(date_probability),
        ),
        amount_confidence=amount_probability,
        date_confidence=date_probability,
        # 판독 근거(structural/largest)는 그대로 보존한다 — 감사 때 "왜 그 값이었나" 를 답해야 한다.
    )
