"""Gemini baseline 프로바이더 테스트.

가장 중요한 건 **운영 어댑터와의 동일성**이다. 프롬프트나 필드 해석이 조금이라도 다르면
"자체 모델이 baseline 을 이겼다" 는 결론이 그 차이 때문일 수 있어 비교가 무너진다.
"""

from __future__ import annotations

import datetime as _dt
import json
import pathlib
import re
from decimal import Decimal

import httpx
import pytest

from receipt_ocr.providers.gemini import (
    FALLBACK_CONFIDENCE,
    PROMPT,
    ExtractionFailed,
    GeminiProvider,
    map_fields,
    parse_envelope,
    strip_code_fence,
)

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
JAVA_ADAPTER = (
    REPO_ROOT / "card-service" / "src" / "main" / "java" / "github" / "lms" / "lemuel"
    / "card" / "adapter" / "out" / "llm" / "GeminiReceiptOcrAdapter.java"
)


def envelope(fields: dict, usage: dict | None = None) -> dict:
    payload = {"candidates": [{"content": {"parts": [{"text": json.dumps(fields, ensure_ascii=False)}]}}]}
    if usage:
        payload["usageMetadata"] = usage
    return payload


class TestPromptParityWithProduction:
    """운영 Java 어댑터의 프롬프트와 문자 단위로 같아야 한다."""

    def test_자바_어댑터_파일이_존재한다(self):
        # 아래 대조 테스트가 조용히 skip 되어 '통과' 로 읽히는 걸 막는 앵커.
        assert JAVA_ADAPTER.exists(), f"운영 어댑터를 찾지 못했습니다: {JAVA_ADAPTER}"

    def test_프롬프트가_운영과_동일하다(self):
        source = JAVA_ADAPTER.read_text(encoding="utf-8")
        match = re.search(r'PROMPT\s*=\s*"""\r?\n(.*?)\r?\n(\s*)""";', source, re.DOTALL)
        assert match, "Java 텍스트 블록에서 PROMPT 를 추출하지 못했습니다"

        body, closing_indent = match.group(1), match.group(2)
        # Java 텍스트 블록은 닫는 구분자 들여쓰기까지 포함해 최소 들여쓰기를 걷어낸다.
        lines = body.replace("\r\n", "\n").split("\n")
        indents = [len(ln) - len(ln.lstrip()) for ln in lines if ln.strip()]
        strip_width = min(indents + [len(closing_indent)])
        java_prompt = "\n".join(ln[strip_width:] if ln.strip() else "" for ln in lines) + "\n"

        assert java_prompt == PROMPT


class TestFieldMappingParity:
    """Java ``mapFields`` 의 해석 규칙을 그대로 따르는지."""

    def test_정상_필드(self):
        result = map_fields({
            "merchantName": "김밥천국 강남점",
            "transactionDate": "2026-03-04",
            "totalAmount": "12300",
            "confidence": "0.93",
        })
        assert result.merchant_name == "김밥천국 강남점"
        assert result.transaction_date == _dt.date(2026, 3, 4)
        assert result.total_amount == Decimal("12300")
        assert result.amount_confidence == Decimal("0.93")

    @pytest.mark.parametrize("raw,expected", [("12,300", "12300"), ("12300원", "12300"),
                                              ("₩ 12,300 ", "12300"), (12300, "12300")])
    def test_총액은_장식을_걷어내고_읽는다(self, raw, expected):
        assert map_fields({"totalAmount": raw}).total_amount == Decimal(expected)

    @pytest.mark.parametrize("raw", [None, "", "  ", "읽을 수 없음", "."])
    def test_총액을_못_읽으면_지어내지_않고_끊는다(self, raw):
        # 총액은 대사의 근거다 — 여기서 추측값을 만들면 증빙 없는 지출이 통과한다.
        with pytest.raises(ExtractionFailed):
            map_fields({"totalAmount": raw})

    @pytest.mark.parametrize("raw", [None, "", "2026/03/04", "작년 3월", "2026-13-45"])
    def test_거래일_판독_실패는_None_이다(self, raw):
        # 불일치 선고 근거가 아니라 리뷰로 흘러야 한다.
        assert map_fields({"totalAmount": "100", "transactionDate": raw}).transaction_date is None

    @pytest.mark.parametrize("raw", [None, "", "높음", "1.5"])
    def test_신뢰도가_없거나_망가지면_보수적_기본값(self, raw):
        result = map_fields({"totalAmount": "100", "confidence": raw})
        assert result.amount_confidence == FALLBACK_CONFIDENCE

    def test_음수_신뢰도는_부호가_사라진다_운영과_공유하는_특성(self):
        # 운영 Java 도 `[^0-9.]` 를 걷어내므로 "-0.2" 는 0.2 가 된다. 부호가 조용히 사라지는
        # 셈이지만 양쪽 결과가 같아야 baseline 이 성립하므로 이식본도 같게 두고 여기 못박는다.
        # (실질 영향은 작다 — 음수든 0.2 든 임계 미만이라 어차피 리뷰 큐로 간다.)
        assert map_fields({"totalAmount": "100", "confidence": "-0.2"}).amount_confidence == Decimal("0.2")

    def test_상호명_공백은_None_으로_정규화(self):
        assert map_fields({"totalAmount": "100", "merchantName": "   "}).merchant_name is None


class TestEnvelopeParsing:
    def test_정상_봉투(self):
        assert parse_envelope(envelope({"totalAmount": "1"})) == {"totalAmount": "1"}

    @pytest.mark.parametrize("payload", [
        {}, {"candidates": []}, {"candidates": [{"content": {"parts": []}}]},
        {"candidates": [{"content": {"parts": [{"text": "   "}]}}]},
    ])
    def test_빈_응답은_실패다(self, payload):
        with pytest.raises(ExtractionFailed):
            parse_envelope(payload)

    def test_JSON이_아니면_실패다(self):
        with pytest.raises(ExtractionFailed):
            parse_envelope({"candidates": [{"content": {"parts": [{"text": "총액은 12300원입니다"}]}}]})

    def test_JSON_배열은_객체가_아니라_실패다(self):
        with pytest.raises(ExtractionFailed):
            parse_envelope({"candidates": [{"content": {"parts": [{"text": "[1,2]"}]}}]})

    def test_코드펜스를_벗겨_읽는다(self):
        text = '```json\n{"totalAmount": "12300"}\n```'
        assert parse_envelope({"candidates": [{"content": {"parts": [{"text": text}]}}]}) == {
            "totalAmount": "12300"
        }

    @pytest.mark.parametrize("text,expected", [
        ('{"a":1}', '{"a":1}'),
        ('```\n{"a":1}\n```', '{"a":1}'),
        ('```json\n{"a":1}\n```', '{"a":1}'),
    ])
    def test_코드펜스_제거_규칙(self, text, expected):
        assert strip_code_fence(text) == expected


class TestProviderCall:
    def _provider(self, handler, **kwargs) -> GeminiProvider:
        transport = httpx.MockTransport(handler)
        return GeminiProvider("test-key", client=httpx.Client(transport=transport), **kwargs)

    def test_성공_호출은_추출과_지연을_돌려준다(self):
        def handler(request: httpx.Request) -> httpx.Response:
            body = json.loads(request.content)
            # 운영과 같은 요청 형태인지 여기서 같이 확인한다.
            assert body["contents"][0]["parts"][0]["text"] == PROMPT
            assert body["contents"][0]["parts"][1]["inline_data"]["mime_type"] == "image/jpeg"
            assert body["generationConfig"]["responseMimeType"] == "application/json"
            assert request.headers["x-goog-api-key"] == "test-key"
            return httpx.Response(200, json=envelope(
                {"totalAmount": "12300", "transactionDate": "2026-03-04", "confidence": "0.9"},
                usage={"promptTokenCount": 1200, "candidatesTokenCount": 40},
            ))

        result = self._provider(handler).extract(b"\xff\xd8fake", "image/jpeg")
        assert result.ok
        assert result.extracted.total_amount == Decimal("12300")
        assert result.latency_ms > 0
        assert (result.prompt_tokens, result.output_tokens) == (1200, 40)

    def test_토큰_단가를_주면_비용을_계산한다(self):
        def handler(request):
            return httpx.Response(200, json=envelope(
                {"totalAmount": "100"}, usage={"promptTokenCount": 1_000_000,
                                               "candidatesTokenCount": 2_000_000}))

        provider = self._provider(handler, price_per_mtok_in=Decimal("0.3"),
                                  price_per_mtok_out=Decimal("2.5"))
        assert provider.extract(b"x", "image/jpeg").cost_usd == Decimal("5.3")

    def test_단가를_안_주면_비용은_0이다(self):
        # 모르는 가격을 지어내지 않는다 — 토큰 수만 보고한다.
        def handler(request):
            return httpx.Response(200, json=envelope(
                {"totalAmount": "100"}, usage={"promptTokenCount": 999, "candidatesTokenCount": 9}))

        assert self._provider(handler).extract(b"x", "image/jpeg").cost_usd == Decimal("0")

    def test_HTTP_오류는_예외가_아니라_실패_결과다(self):
        # 한 건의 실패로 전체 평가를 잃으면 안 된다. 실패율 자체가 측정 대상이다.
        result = self._provider(lambda r: httpx.Response(500, text="boom")).extract(b"x", "image/jpeg")
        assert not result.ok and result.error
        assert "boom" in result.raw

    def test_총액_판독_실패도_실패_결과다(self):
        handler = lambda r: httpx.Response(200, json=envelope({"merchantName": "가게"}))
        result = self._provider(handler).extract(b"x", "image/jpeg")
        assert not result.ok
        assert "총액" in result.error

    def test_API키가_없으면_호출하지_않고_실패한다(self):
        result = GeminiProvider("").extract(b"x", "image/jpeg")
        assert not result.ok and not GeminiProvider("").configured

    def test_이름에_모델명이_들어간다(self):
        # 리포트만 보고 어떤 모델이었는지 알 수 있어야 재현이 된다.
        assert GeminiProvider("k", model="gemini-2.5-flash").name == "gemini:gemini-2.5-flash"
