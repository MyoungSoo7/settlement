"""Gemini 비전 baseline — **운영 어댑터와 같은 값을 내도록** 이식한 것이다.

대조군이 대조군이려면 운영과 같아야 한다. 그래서 프롬프트 문자열, 응답 봉투 해체, 필드 해석,
신뢰도 기본값까지 Java 쪽 ``GeminiReceiptOcrAdapter`` + ``VisionExtractionClient`` 를 그대로 옮겼다.
여기서 한 글자라도 다르게 만들면 "자체 모델이 baseline 을 이겼다" 는 결론이 프롬프트 차이 때문일
수 있게 되고, 비교 자체가 무의미해진다.

옮긴 규칙(정본은 Java 쪽):

* 총액은 ``[^0-9.]`` 를 걷어낸 뒤 파싱 — 실패하면 **지어내지 않고 끊는다**(운영 503).
* 거래일은 ``YYYY-MM-DD`` 파싱 실패 시 null — 불일치 선고가 아니라 리뷰로 흐른다.
* 신뢰도는 누락·파손·범위 밖이면 보수적 기본값 0.50 — 리뷰 큐로 가게 한다.
"""

from __future__ import annotations

import datetime as _dt
import json
import re
import time
from decimal import Decimal, InvalidOperation

import httpx

from ..domain.extracted import ExtractedReceipt
from .base import ExtractionResult

#: Java 어댑터의 PROMPT 와 **문자 단위로 같아야 한다**.
PROMPT = """첨부한 한국 카드 결제 영수증 이미지에서 다음 필드를 읽어 JSON 으로만 답하라.
추측하지 말고, 읽을 수 없는 필드는 null 로 둔다.
{
  "merchantName": "상호명",
  "transactionDate": "거래일(YYYY-MM-DD)",
  "totalAmount": "결제 총액(숫자만)",
  "confidence": "판독 신뢰도 0~1"
}
"""

#: 모델이 신뢰도를 주지 않았을 때의 보수적 기본값 — 리뷰 큐로 흐르게 한다.
FALLBACK_CONFIDENCE = Decimal("0.50")

_NON_NUMERIC = re.compile(r"[^0-9.]")


class ExtractionFailed(Exception):
    """추출 불가 — 운영의 ``CARD_RECEIPT_OCR_UNAVAILABLE``(503) 에 대응."""


def _text(fields: dict, name: str) -> str | None:
    """Java ``text(JsonNode, String)`` 대응 — 누락·null·공백은 전부 None."""
    value = fields.get(name)
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _transaction_date(raw: str | None) -> _dt.date | None:
    """거래일 — 파싱 실패는 None(지어내지 않는다)."""
    if raw is None:
        return None
    try:
        return _dt.date.fromisoformat(raw)
    except ValueError:
        return None


def _total_amount(raw: str | None) -> Decimal:
    """총액 — 장식을 걷어내고 숫자만. 못 읽으면 끊는다(대사의 근거라 지어낼 수 없다)."""
    digits = _NON_NUMERIC.sub("", raw or "")
    if not digits or digits == ".":
        raise ExtractionFailed(f"영수증 총액을 읽지 못했습니다: {raw}")
    try:
        return Decimal(digits)
    except InvalidOperation as exc:
        raise ExtractionFailed(f"영수증 총액을 읽지 못했습니다: {raw}") from exc


def _confidence(raw: str | None) -> Decimal:
    """신뢰도 — 누락·파손·범위 밖은 전부 보수적 기본값."""
    if raw is None:
        return FALLBACK_CONFIDENCE
    try:
        parsed = Decimal(_NON_NUMERIC.sub("", raw))
    except InvalidOperation:
        return FALLBACK_CONFIDENCE
    if parsed < 0 or parsed > 1:
        return FALLBACK_CONFIDENCE
    return parsed


def map_fields(fields: dict) -> ExtractedReceipt:
    """모델 JSON → 추출 결과. **필드 해석의 정본은 Java 쪽이고 여기는 그 이식본이다.**"""
    return ExtractedReceipt(
        merchant_name=_text(fields, "merchantName"),
        transaction_date=_transaction_date(_text(fields, "transactionDate")),
        total_amount=_total_amount(_text(fields, "totalAmount")),
        confidence=_confidence(_text(fields, "confidence")),
    )


def strip_code_fence(text: str) -> str:
    """```json 으로 감싸 오는 흔한 습관을 벗긴다 — Java ``stripCodeFence`` 대응."""
    trimmed = text.strip()
    if not trimmed.startswith("```"):
        return trimmed
    newline = trimmed.find("\n")
    body = "" if newline < 0 else trimmed[newline + 1:]
    closing = body.rfind("```")
    return (body if closing < 0 else body[:closing]).strip()


def parse_envelope(payload: dict) -> dict:
    """``candidates[0].content.parts[0].text`` 안의 JSON 객체를 꺼낸다."""
    candidates = payload.get("candidates")
    if not isinstance(candidates, list) or not candidates:
        raise ExtractionFailed("비전 추출이 빈 응답을 반환했습니다.")
    parts = candidates[0].get("content", {}).get("parts") or []
    text = parts[0].get("text", "") if parts else ""
    if not text.strip():
        raise ExtractionFailed("비전 추출이 빈 응답을 반환했습니다.")
    try:
        fields = json.loads(strip_code_fence(text))
    except json.JSONDecodeError as exc:
        raise ExtractionFailed("비전 추출 응답이 JSON 형식이 아닙니다.") from exc
    if not isinstance(fields, dict):
        raise ExtractionFailed("비전 추출 응답이 JSON 객체가 아닙니다.")
    return fields


class GeminiProvider:
    """운영과 동일한 호출로 baseline 을 잰다."""

    def __init__(self, api_key: str, *, model: str = "gemini-2.5-flash",
                 base_url: str = "https://generativelanguage.googleapis.com",
                 max_output_tokens: int = 1024, timeout: float = 60.0,
                 client: httpx.Client | None = None,
                 price_per_mtok_in: Decimal = Decimal("0"),
                 price_per_mtok_out: Decimal = Decimal("0")):
        self._api_key = (api_key or "").strip()
        self._model = model
        self._base_url = base_url.rstrip("/")
        self._max_output_tokens = max_output_tokens
        self._timeout = timeout
        self._client = client
        self._price_in = price_per_mtok_in
        self._price_out = price_per_mtok_out

    @property
    def name(self) -> str:
        return f"gemini:{self._model}"

    @property
    def configured(self) -> bool:
        return bool(self._api_key)

    def _body(self, content: bytes, content_type: str) -> dict:
        import base64

        return {
            "contents": [{
                "role": "user",
                "parts": [
                    {"text": PROMPT},
                    {"inline_data": {
                        "mime_type": content_type or "image/png",
                        "data": base64.b64encode(content).decode("ascii"),
                    }},
                ],
            }],
            "generationConfig": {
                "responseMimeType": "application/json",
                "maxOutputTokens": self._max_output_tokens,
            },
        }

    def _cost(self, prompt_tokens: int, output_tokens: int) -> Decimal:
        """토큰 × 단가. **단가를 안 주면 0 이다** — 모르는 가격을 지어내지 않는다."""
        million = Decimal("1000000")
        return (Decimal(prompt_tokens) / million * self._price_in
                + Decimal(output_tokens) / million * self._price_out)

    def extract(self, content: bytes, content_type: str) -> ExtractionResult:
        if not self.configured:
            return ExtractionResult(None, 0.0, error="API 키 미설정 — 운영이라면 503 입니다.")

        url = f"{self._base_url}/v1beta/models/{self._model}:generateContent"
        started = time.perf_counter()
        raw = ""
        try:
            client = self._client or httpx.Client(timeout=self._timeout)
            try:
                response = client.post(
                    url,
                    headers={"x-goog-api-key": self._api_key, "content-type": "application/json"},
                    json=self._body(content, content_type),
                )
                raw = response.text
                response.raise_for_status()
                payload = response.json()
            finally:
                if self._client is None:
                    client.close()

            usage = payload.get("usageMetadata", {}) or {}
            prompt_tokens = int(usage.get("promptTokenCount", 0) or 0)
            output_tokens = int(usage.get("candidatesTokenCount", 0) or 0)
            extracted = map_fields(parse_envelope(payload))
            elapsed = (time.perf_counter() - started) * 1000
            return ExtractionResult(
                extracted=extracted,
                latency_ms=elapsed,
                raw=raw,
                prompt_tokens=prompt_tokens,
                output_tokens=output_tokens,
                cost_usd=self._cost(prompt_tokens, output_tokens),
            )
        except (ExtractionFailed, ValueError, httpx.HTTPError) as exc:
            # 무폴백 — 부분 결과를 만들어 내지 않고 실패로 센다.
            return ExtractionResult(
                None, (time.perf_counter() - started) * 1000,
                error=f"{type(exc).__name__}: {exc}", raw=raw,
            )
