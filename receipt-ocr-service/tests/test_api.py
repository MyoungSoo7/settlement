"""서빙 계약 테스트 — Java 어댑터가 의존할 응답 모양을 못박는다.

여기 계약이 흔들리면 Phase 3 에서 포트를 갈아끼울 때 조용히 깨진다.
"""

from __future__ import annotations

import datetime as _dt
from decimal import Decimal

import pytest
from fastapi.testclient import TestClient

from receipt_ocr.api.app import MAX_BYTES, create_app
from receipt_ocr.domain.extracted import ExtractedReceipt
from receipt_ocr.providers.parsing import ParseFailed, ParsedReceipt


class StubProvider:
    """엔진 없이 계약만 검증한다."""

    name = "stub"

    def __init__(self, parsed=None, raises=None):
        self._parsed = parsed
        self._raises = raises

    def parse(self, content: bytes):
        if self._raises:
            raise self._raises
        return self._parsed


def parsed(date=_dt.date(2026, 3, 9), amount_conf=0.91, date_conf=0.44) -> ParsedReceipt:
    return ParsedReceipt(
        extracted=ExtractedReceipt(
            merchant_name=None,
            transaction_date=date,
            total_amount=Decimal("64100"),
            amount_confidence=Decimal(str(amount_conf)),
            date_confidence=None if date is None else Decimal(str(date_conf)),
        ),
        amount_confidence=amount_conf,
        date_confidence=date_conf,
        amount_method="structural",
        amount_source="64,100",
        date_source="2026-03-09",
    )


def client(provider) -> TestClient:
    return TestClient(create_app(provider))


class TestHealth:
    def test_모델명을_함께_알린다(self):
        # 어떤 구성이 떠 있는지 밖에서 확인할 수 있어야 한다.
        body = client(StubProvider(parsed())).get("/health").json()
        assert body["status"] == "UP" and body["model"] == "stub"


class TestExtractSuccess:
    def test_운영_포트_필드를_그대로_돌려준다(self):
        body = client(StubProvider(parsed())).post("/extract", content=b"img").json()
        assert body["totalAmount"] == "64100"
        assert body["transactionDate"] == "2026-03-09"
        assert body["merchantName"] is None

    def test_금액과_신뢰도는_문자열이다(self):
        # JSON number 로 보내면 받는 쪽에서 float 이 되어 원 단위가 흔들린다.
        body = client(StubProvider(parsed())).post("/extract", content=b"img").json()
        assert isinstance(body["totalAmount"], str)
        assert isinstance(body["confidence"], str)

    def test_필드별_신뢰도를_함께_싣는다(self):
        # baseline 의 치명 오류가 이 부재에서 났다 — Java 가 아직 못 받아도 서버는 내보낸다.
        body = client(StubProvider(parsed())).post("/extract", content=b"img").json()
        assert body["fieldConfidence"] == {"amount": 0.91, "date": 0.44}

    def test_거래일_판독_실패는_null_이다(self):
        body = client(StubProvider(parsed(date=None, date_conf=None))).post(
            "/extract", content=b"img").json()
        assert body["transactionDate"] is None
        assert body["fieldConfidence"]["date"] is None

    def test_총액을_고른_근거를_알린다(self):
        body = client(StubProvider(parsed())).post("/extract", content=b"img").json()
        assert body["amountMethod"] == "structural"


class TestExtractFailure:
    def test_총액을_못_읽으면_503이다(self):
        # 무폴백 — Java 쪽 CARD_RECEIPT_OCR_UNAVAILABLE 로 그대로 옮겨진다.
        response = client(StubProvider(raises=ParseFailed("총액 후보 없음"))).post(
            "/extract", content=b"img")
        assert response.status_code == 503
        assert "총액" in response.json()["error"]

    def test_엔진이_뻗어도_503이다(self):
        response = client(StubProvider(raises=RuntimeError("onnx 붕괴"))).post(
            "/extract", content=b"img")
        assert response.status_code == 503
        assert "onnx 붕괴" in response.json()["error"]

    def test_빈_본문은_400이다(self):
        assert client(StubProvider(parsed())).post("/extract", content=b"").status_code == 400

    def test_너무_큰_이미지는_413이다(self):
        oversized = b"x" * (MAX_BYTES + 1)
        assert client(StubProvider(parsed())).post(
            "/extract", content=oversized).status_code == 413
