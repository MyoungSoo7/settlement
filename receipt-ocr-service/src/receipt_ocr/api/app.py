"""HTTP 서빙 — Java 어댑터(``LocalVisionReceiptOcrAdapter``, Phase 3)가 부를 자리.

**계약은 운영 포트를 따른다** (``ExtractReceiptFieldsPort``):

* 성공 200 — 총액은 항상 있고, 상호명·거래일은 판독 실패를 ``null`` 로 표현한다.
* 실패 503 — 총액을 못 읽으면 **부분 결과를 지어내지 않고** 끊는다(무폴백, ADR 0036).
  Java 쪽은 이걸 ``CARD_RECEIPT_OCR_UNAVAILABLE`` 로 옮기면 된다.

응답에는 운영 포트가 요구하는 스칼라 ``confidence`` 외에 **필드별 신뢰도**(``fieldConfidence``)를
함께 싣는다. 아직 Java 포트가 받지 못하지만, baseline 의 치명 오류가 정확히 그 부재에서 났으므로
서버는 먼저 내보낸다.

이미지는 **요청 본문에 그대로** 받는다(multipart 아님) — ``python-multipart`` 의존성을 늘리지 않고,
Java ``RestClient`` 에서 바이트를 그대로 실어 보내기 쉽다.
"""

from __future__ import annotations

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from ..providers.local_ocr import RapidOcrProvider
from ..providers.parsing import ParseFailed

#: 업로드 상한 — 휴대폰 사진 한 장이다. 이보다 크면 받을 이유가 없다.
MAX_BYTES = 12 * 1024 * 1024


def create_app(provider: RapidOcrProvider | None = None) -> FastAPI:
    """앱을 만든다. ``provider`` 는 테스트·구성 교체 지점."""
    app = FastAPI(title="receipt-ocr-service", version="0.1.0")
    engine = provider if provider is not None else RapidOcrProvider(preprocess=True)

    @app.get("/health")
    def health() -> dict:
        return {"status": "UP", "model": engine.name}

    @app.post("/extract")
    async def extract(request: Request):
        content = await request.body()
        if not content:
            return JSONResponse(status_code=400, content={"error": "빈 요청 본문입니다."})
        if len(content) > MAX_BYTES:
            return JSONResponse(
                status_code=413,
                content={"error": f"이미지가 너무 큽니다: {len(content)} > {MAX_BYTES}"},
            )

        try:
            parsed = engine.parse(content)
        except ParseFailed as exc:
            # 총액은 대사의 근거다 — 못 읽으면 지어내지 않고 503.
            return JSONResponse(status_code=503, content={"error": str(exc), "model": engine.name})
        except Exception as exc:  # 엔진 붕괴도 같은 계약으로 돌려준다
            return JSONResponse(
                status_code=503,
                content={"error": f"{type(exc).__name__}: {exc}", "model": engine.name},
            )

        extracted = parsed.extracted
        return {
            "merchantName": extracted.merchant_name,
            "transactionDate": (
                extracted.transaction_date.isoformat() if extracted.transaction_date else None
            ),
            # 금액은 문자열이다 — JSON number 로 보내면 받는 쪽에서 float 이 되어 원 단위가 흔들린다.
            "totalAmount": str(extracted.total_amount),
            "confidence": str(extracted.weakest_confidence),
            "fieldConfidence": {
                "amount": round(parsed.amount_confidence, 4),
                "date": None if parsed.date_confidence is None else round(parsed.date_confidence, 4),
            },
            "amountMethod": parsed.amount_method,
            "model": engine.name,
        }

    return app


app = create_app()
