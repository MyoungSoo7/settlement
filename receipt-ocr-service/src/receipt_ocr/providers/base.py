"""추출 프로바이더 계약 — baseline(외부 LLM)과 자체 모델을 같은 자리에 꽂기 위한 인터페이스.

Java 쪽 ``ExtractReceiptFieldsPort`` 와 같은 역할이다. 평가 러너는 이 계약만 알고, 뒤에 무엇이
있는지(원격 API 인지 로컬 모델인지)는 모른다 — 그래야 같은 하네스로 공정하게 비교할 수 있다.
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from typing import Protocol, runtime_checkable

from ..domain.extracted import ExtractedReceipt


@dataclass(frozen=True)
class ExtractionResult:
    """추출 1회의 결과 + 원가 계측.

    :param extracted: 추출 성공 시 결과. **실패는 None** — 부분 결과를 지어내지 않는다(무폴백).
    :param error: 실패 사유. 운영의 503 메시지에 대응한다.
    :param raw: 모델 원문 응답. 왜 틀렸는지 사후에 보려면 이게 있어야 한다.
    :param prompt_tokens: 입력 토큰 수(제공하는 프로바이더만).
    :param output_tokens: 출력 토큰 수(제공하는 프로바이더만).
    """

    extracted: ExtractedReceipt | None
    latency_ms: float
    error: str = ""
    raw: str = ""
    prompt_tokens: int = 0
    output_tokens: int = 0
    cost_usd: Decimal = Decimal("0")

    @property
    def ok(self) -> bool:
        return self.extracted is not None


@runtime_checkable
class Provider(Protocol):
    """이미지 바이트 → 영수증 필드."""

    @property
    def name(self) -> str:
        """리포트에 찍히는 식별자 — 모델 버전까지 포함해야 재현이 된다."""

    def extract(self, content: bytes, content_type: str) -> ExtractionResult:
        """추출을 시도한다. 실패는 예외가 아니라 ``extracted=None`` 인 결과로 돌려준다.

        러너가 한 건의 실패로 전체 평가를 잃지 않도록 예외 대신 결과로 표현한다 — 실패율 자체가
        측정 대상이기도 하다(``unavailable_rate``).
        """
