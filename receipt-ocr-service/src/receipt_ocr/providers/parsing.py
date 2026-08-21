"""OCR 텍스트 → 영수증 필드. **무엇이 총액인가**를 정하는 도메인 지식이 여기 산다.

OCR 엔진은 '글자와 박스' 까지만 준다. 영수증에는 금액이 여럿 찍혀 있고(품목·소계·할인·
공급가액·부가세·합계) 그중 하나만 대사의 근거다. 그걸 고르는 규칙이 이 모듈이다.

세 가지 근거를 이 순서로 쓴다.

1. **구조 검증** — 한국 영수증은 ``공급가액 + 부가세 = 합계`` 다. 읽어낸 금액들 중 이 관계를
   만족하는 조합이 있으면 그 합이 총액이다. 글자 크기 추측보다 훨씬 강하고, **OCR 점수와
   독립인 근거**라 신뢰도를 올릴 자격이 있다. (외부 LLM 은 이 검산을 하지 않는다.)
2. **글자 크기** — 합계 줄은 다른 줄보다 큰 폰트로 찍힌다.
3. 둘 다 실패하면 지어내지 않고 끊는다.

그리고 **필드마다 자기 신뢰도**를 들고 다닌다. baseline 의 치명 오류가 정확히 여기서 났다 —
신뢰도가 추출 전체에 대한 스칼라 하나라, 또렷한 총액의 확신이 반사광에 덮인 거래일의
불확실성을 덮어버렸다.
"""

from __future__ import annotations

import datetime as _dt
import re
from dataclasses import dataclass
from decimal import Decimal
from typing import Any

from ..domain.extracted import ExtractedReceipt

#: 금액은 콤마 3자리 묶음으로만 인정한다 — 영수증은 금액을 항상 그렇게 찍는다.
#: 이 제약이 전화번호·사업자번호·승인번호·카드번호를 후보에서 통째로 걷어낸다.
_AMOUNT = re.compile(r"(?<![\d,])(\d{1,3}(?:,\d{3})+)(?![\d,])")

#: 4자리 연도 — 구분자는 OCR 이 뭉갤 수 있으니 느슨하게 본다(``년/월`` 이 한자로 오독된다).
#: 실측 훼손을 견디도록 느슨하다. RapidOCR 은 공백을 먹어 ``2026/03/1900:52`` 로 붙여 놓고,
#: 앞에 잡음 숫자를 하나 더 붙이기도 한다(``22026/...``). 그래서 양끝을 ``(?<!\d)``/``(?!\d)``
#: 로 막지 않는다 — 막았더니 이런 줄을 통째로 놓쳐 거래일 판독률이 20% 대로 주저앉았다.
#:
#: 대신 월·일을 **2자리 고정**으로 받는다. ``\d{1,2}`` 로 열어 두면 ``2026-13-45`` 같은 훼손된
#: 줄에서 정규식이 되돌아가며 2026-01-03 같은 없는 날짜를 지어낸다.
_DATE_Y4 = re.compile(r"(20\d{2})\D{0,3}(\d{2})\D{0,3}(\d{2})")

#: 거래일시에는 시각이 붙는다 — 승인번호 같은 긴 숫자에서 우연히 날짜 모양이 나오는 것과
#: 진짜 거래일을 가르는 신호다.
_TIME = re.compile(r"\d{1,2}:\d{2}")
#: 2자리 연도 점 표기 — ``26.03.04``.
_DATE_Y2 = re.compile(r"(?<!\d)(\d{2})\.(\d{2})\.(\d{2})(?!\d)")

#: 구조 검증이 성립했을 때 총액 신뢰도에 더해 주는 몫.
STRUCTURAL_BONUS = 0.15

#: 구조 검증이 실패해 글자 크기로 추측했을 때 깎는 몫.
#:
#: **판독 방법 자체가 정확도에 대한 증거다.** 구조가 안 맞는다는 건 금액 중 하나를 잘못 읽었다는
#: 뜻이고, 그 상태의 '가장 큰 글씨' 는 믿을 게 못 된다 — 실측에서 부가세(1,641)를 합계(18,048)
#: 대신 고른 경우가 있었다. OCR 토큰 점수는 "이 글자를 이렇게 읽었다" 는 확신일 뿐 "이 값이
#: 총액이다" 라는 확신이 아니므로, 그 점수를 그대로 쓰면 틀린 총액이 임계를 넘어 종결된다.
#: 비대칭 비용(오종결 25 vs 리뷰 1)에서는 모를 때 리뷰로 보내는 쪽이 항상 싸다.
LARGEST_PENALTY = 0.25


@dataclass(frozen=True)
class OcrLine:
    """OCR 이 돌려준 텍스트 한 조각.

    :param height: 텍스트 박스 높이 — 합계 줄을 찾는 단서다(큰 폰트).
    :param top: 세로 위치. 지금은 안 쓰지만 감사·디버깅에 필요하다.
    """

    text: str
    confidence: float
    height: float = 0.0
    top: float = 0.0


@dataclass(frozen=True)
class FieldParse:
    """필드 1개의 판독 결과 + **그 필드 자신의** 신뢰도.

    :param method: 어떤 근거로 골랐는지 (``structural`` / ``largest`` / ``regex``) — 감사용.
    :param source: 어느 텍스트에서 왔는지 — 왜 틀렸는지 사후에 보려면 이게 있어야 한다.
    """

    value: Any
    confidence: float
    method: str
    source: str


@dataclass(frozen=True)
class ParsedReceipt:
    """파싱 결과 — 운영 계약(:class:`ExtractedReceipt`) + 필드별 신뢰도.

    ``extracted.confidence`` 는 운영 포트가 요구하는 스칼라 하나라서, 필드별 값을 **보수적으로**
    합친 것이다(아래 :func:`combine_confidence`). 필드별 값 자체는 Java 포트가 필드별 신뢰도를
    받게 되는 날을 위해 따로 보존한다.
    """

    extracted: ExtractedReceipt
    amount_confidence: float
    date_confidence: float | None
    amount_method: str
    amount_source: str
    date_source: str | None


class ParseFailed(Exception):
    """총액을 못 읽었다 — 대사의 근거라 지어낼 수 없다(운영의 503)."""


def _amount_candidates(lines: list[OcrLine]) -> list[tuple[Decimal, OcrLine, str]]:
    """콤마 묶음 금액을 전부 걷는다. 앞에 ``-`` 가 붙은 차감액은 제외한다."""
    found: list[tuple[Decimal, OcrLine, str]] = []
    for line in lines:
        for match in _AMOUNT.finditer(line.text):
            # "할인 -1,152" 처럼 바로 앞이 마이너스면 차감액이다 — 총액 후보가 아니다.
            if match.start() > 0 and line.text[match.start() - 1] == "-":
                continue
            found.append((Decimal(match.group(1).replace(",", "")), line, match.group(1)))
    return found


def _structural_total(candidates: list[tuple[Decimal, OcrLine, str]]):
    """``공급가액 + 부가세 = 합계`` 를 만족하는 조합을 찾는다.

    합이 되는 c 가 여러 개면 가장 큰 값을 고른다 — 소계까지 걸리는 경우 합계가 더 크다.
    """
    values = [value for value, _, _ in candidates]
    winners = []
    for index, (total, line, source) in enumerate(candidates):
        others = [v for position, v in enumerate(values) if position != index]
        if any(a + b == total for i, a in enumerate(others) for b in others[i + 1:]):
            winners.append((total, line, source))
    if not winners:
        return None
    return max(winners, key=lambda item: item[0])


def find_amount(lines: list[OcrLine]) -> FieldParse | None:
    """총액을 고른다. 구조 검증 → 글자 크기 순."""
    candidates = _amount_candidates(lines)
    if not candidates:
        return None

    structural = _structural_total(candidates)
    if structural is not None:
        total, line, source = structural
        # 구조가 맞아떨어진 건 OCR 점수와 독립인 근거다 — 신뢰도를 올릴 자격이 있다.
        return FieldParse(total, min(1.0, line.confidence + STRUCTURAL_BONUS), "structural", source)

    total, line, source = max(candidates, key=lambda item: (item[1].height, item[0]))
    return FieldParse(total, max(0.0, line.confidence - LARGEST_PENALTY), "largest", source)


def _to_date(year: int, month: int, day: int) -> _dt.date | None:
    """말이 안 되는 날짜는 버린다 — 지어내지 않는다."""
    try:
        return _dt.date(year, month, day)
    except ValueError:
        return None


def find_date(lines: list[OcrLine]) -> FieldParse | None:
    """거래일을 고른다.

    후보가 여럿이면 **시각을 동반한 줄**을 먼저 믿고, 그다음 OCR 점수를 본다.
    """
    found: list[tuple[bool, float, FieldParse]] = []
    for line in lines:
        has_time = bool(_TIME.search(line.text))
        for match in _DATE_Y4.finditer(line.text):
            parsed = _to_date(int(match.group(1)), int(match.group(2)), int(match.group(3)))
            if parsed:
                found.append((has_time, line.confidence,
                              FieldParse(parsed, line.confidence, "regex", match.group(0))))
        for match in _DATE_Y2.finditer(line.text):
            parsed = _to_date(2000 + int(match.group(1)), int(match.group(2)), int(match.group(3)))
            if parsed:
                found.append((has_time, line.confidence,
                              FieldParse(parsed, line.confidence, "regex", match.group(0))))
    if not found:
        return None
    return max(found, key=lambda item: (item[0], item[1]))[2]


def to_confidence(value: float) -> Decimal:
    """OCR 점수를 0~1 Decimal 로 정규화한다.

    **합치지 않는다.** 운영 포트가 필드별 신뢰도를 받게 된 뒤로는 두 값을 하나로 줄일 이유가
    없다 — 줄이는 순간 이 타입이 없애려던 결함(쉬운 필드가 어려운 필드를 덮는 것)이 돌아온다.
    """
    return Decimal(str(round(max(0.0, min(1.0, value)), 4)))


def parse_receipt(lines: list[OcrLine]) -> ParsedReceipt:
    """OCR 결과 전체를 영수증 추출 결과로 옮긴다.

    :raises ParseFailed: 총액을 못 읽은 경우 — 부분 결과를 만들어 내지 않는다.
    """
    amount = find_amount(lines)
    if amount is None:
        raise ParseFailed("영수증 총액을 읽지 못했습니다 (콤마 표기 금액 후보 없음)")

    date = find_date(lines)
    return ParsedReceipt(
        extracted=ExtractedReceipt(
            merchant_name=None,  # 상호명은 대사 판정에 쓰이지 않는다 — 읽지 않는다.
            transaction_date=date.value if date else None,
            total_amount=amount.value,
            amount_confidence=to_confidence(amount.confidence),
            date_confidence=to_confidence(date.confidence) if date else None,
        ),
        amount_confidence=amount.confidence,
        date_confidence=date.confidence if date else None,
        amount_method=amount.method,
        amount_source=amount.source,
        date_source=date.source if date else None,
    )
