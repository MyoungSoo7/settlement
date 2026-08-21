"""도메인 채점기 — 필드 정확도가 아니라 **최종 대사 판정**으로 모델을 채점한다.

필드 정확도(총액 일치율)로 채점하면 운영에서 아무 의미 없는 숫자가 나온다. 실제로 갈리는 건
:mod:`receipt_ocr.domain.matcher` 가 내리는 판정이고, 그 판정마다 사람이 치르는 비용이 다르다.

**오답 비용은 비대칭이다.** 자신 없을 때 리뷰 큐로 보내는 건 설계된 안전 경로라 비용이 싸다.
반대로 기계가 멋대로 종결시킨 오답은 비싸다 — ``MISMATCHED`` 는 종결 상태라 되돌리려면 영수증을
다시 첨부해야 하고, ``MATCHED`` 오종결은 증빙 없는 지출을 그대로 승인한다. 그래서 정확도 하나로
줄이지 않고 **치명 오류 두 종류를 따로 센다**.
"""

from __future__ import annotations

import datetime as _dt
import math
from collections import Counter
from dataclasses import dataclass, field
from decimal import Decimal

from ..domain.extracted import ExtractedReceipt
from ..domain.matcher import DATE_TOLERANCE_DAYS, Outcome, decide

# --------------------------------------------------------------------------------------
# 비용 가중치 — 정책 가정이다(측정값이 아니다). 바꾸면 리포트의 weighted_cost 해석도 바뀐다.
# --------------------------------------------------------------------------------------

#: 사람이 리뷰 큐에서 눈으로 확인하는 비용. 설계된 안전 경로라 싸다.
COST_REVIEW = 1
#: 추출 실패(503) — 사용자가 재시도하거나 수기로 처리한다.
COST_UNAVAILABLE = 3
#: 사람이 봤어야 할 건(정답이 리뷰)을 기계가 멋대로 종결시켰다.
COST_PREMATURE_CLOSE = 10
#: 멀쩡한 영수증을 MISMATCHED 로 오종결 — 종결 상태라 재첨부 외에 되돌릴 방법이 없다.
COST_FALSE_MISMATCH = 25
#: 증빙이 실제로는 맞지 않는데 MATCHED 로 통과 — 증빙 없는 지출이 승인된다.
COST_FALSE_MATCH = 25


def outcome_cost(truth: Outcome, pred: Outcome) -> int:
    """판정 쌍 하나의 비용. 위 가중치 상수의 적용 순서가 곧 정책이다."""
    if pred is truth:
        return 0
    if pred is Outcome.UNAVAILABLE:
        return COST_UNAVAILABLE
    if pred is Outcome.NEEDS_REVIEW:
        return COST_REVIEW
    if truth is Outcome.NEEDS_REVIEW:
        return COST_PREMATURE_CLOSE
    if truth is Outcome.MATCHED and pred is Outcome.MISMATCHED:
        return COST_FALSE_MISMATCH
    if truth is Outcome.MISMATCHED and pred is Outcome.MATCHED:
        return COST_FALSE_MATCH
    return COST_PREMATURE_CLOSE


# --------------------------------------------------------------------------------------
# 골든셋
# --------------------------------------------------------------------------------------


@dataclass(frozen=True)
class CaptureRef:
    """대사의 나머지 축 — 카드 매입 1건 (``CardCapture``)."""

    capture_id: str
    amount: Decimal
    captured_at: _dt.datetime


@dataclass(frozen=True)
class GoldenCase:
    """평가 케이스 1건 — 영수증 이미지 + 사람이 보증한 정답 + 대응 매입.

    :param truth_amount: 영수증에 **실제로 인쇄된** 총액. 매입 금액과 일부러 다른 적대 케이스가 있다.
    :param truth_date: 영수증에 실제로 인쇄된 거래일. 인쇄가 없으면 None.
    :param note: 케이스 성격 메모 (예: "감열지 퇴색", "금액 조작 시나리오") — 실패 분석용.
    :param scenario: 영수증↔매입 관계 태그. 실패를 시나리오별로 잘라 보기 위한 것.
    :param condition: 촬영·인쇄 품질 태그. "저조도에서만 무너지는가" 를 보기 위한 것.
    """

    case_id: str
    capture: CaptureRef
    truth_amount: Decimal
    truth_date: _dt.date | None
    image_path: str | None = None
    note: str = ""
    scenario: str = ""
    condition: str = ""

    def truth_extraction(self) -> ExtractedReceipt:
        """'완벽한 판독' — 정답은 신뢰도 1.0 이다."""
        return ExtractedReceipt(
            merchant_name=None,
            transaction_date=self.truth_date,
            total_amount=self.truth_amount,
            confidence=Decimal("1"),
        )

    def truth_outcome(self, review_threshold: Decimal) -> Outcome:
        """정답 판정 — 완벽한 추출을 운영과 **같은 대사 규칙**에 통과시켜 얻는다."""
        return decide(
            self.truth_extraction(), self.capture.amount, self.capture.captured_at, review_threshold
        ).outcome


@dataclass(frozen=True)
class Prediction:
    """모델 출력 1건. ``extracted is None`` 이면 추출 실패(운영에서는 503)."""

    case_id: str
    extracted: ExtractedReceipt | None
    latency_ms: float = 0.0
    cost_usd: Decimal = Decimal("0")
    error: str = ""


# --------------------------------------------------------------------------------------
# 통계 유틸
# --------------------------------------------------------------------------------------


def percentile(values: list[float], pct: float) -> float:
    """최근접 순위법(nearest-rank) 백분위. 표본이 적어도 실제 관측값을 돌려준다."""
    if not values:
        return 0.0
    ordered = sorted(values)
    rank = max(1, math.ceil(pct / 100.0 * len(ordered)))
    return float(ordered[min(rank, len(ordered)) - 1])


def expected_calibration_error(pairs: list[tuple[float, bool]], bins: int = 10) -> float:
    """ECE — 모델이 주장한 신뢰도와 실제 정답률의 괴리.

    이 프로젝트에서 정확도만큼 중요한 지표다. 임계(기본 0.80) 미만이면 값과 무관하게 리뷰 큐로
    가기 때문에, **과신하는 모델은 오답을 종결시키고 과소평가하는 모델은 리뷰 큐를 넘치게 한다.**

    :param pairs: ``(confidence, 정답 여부)`` 목록. 추출 실패는 신뢰도가 없으므로 제외한다.
    """
    if not pairs:
        return 0.0
    buckets: dict[int, list[tuple[float, bool]]] = {}
    for confidence, correct in pairs:
        # 1.0 은 마지막 구간에 포함시킨다(자기 혼자 빈 구간을 만들지 않도록).
        index = min(int(confidence * bins), bins - 1)
        buckets.setdefault(index, []).append((confidence, correct))

    total = len(pairs)
    ece = 0.0
    for bucket in buckets.values():
        weight = len(bucket) / total
        avg_confidence = sum(c for c, _ in bucket) / len(bucket)
        accuracy = sum(1 for _, ok in bucket if ok) / len(bucket)
        ece += weight * abs(accuracy - avg_confidence)
    return ece


# --------------------------------------------------------------------------------------
# 리포트
# --------------------------------------------------------------------------------------


@dataclass
class EvalReport:
    """채점 결과. ``accuracy`` 하나만 보지 말 것 — 치명 오류 두 칸이 진짜 합격 기준이다."""

    n: int
    accuracy: float
    confusion: dict[tuple[Outcome, Outcome], int]
    critical_false_mismatch: int
    critical_false_match: int
    premature_close: int
    review_rate: float
    unavailable_rate: float
    amount_exact_rate: float
    date_within_tolerance_rate: float
    ece: float
    latency_p50_ms: float
    latency_p95_ms: float
    total_cost_usd: Decimal
    weighted_cost: int
    failures: list[str] = field(default_factory=list)

    @property
    def weighted_cost_per_case(self) -> float:
        return self.weighted_cost / self.n if self.n else 0.0

    @property
    def critical_total(self) -> int:
        return self.critical_false_mismatch + self.critical_false_match


def _date_within_tolerance(truth: _dt.date | None, predicted: _dt.date | None) -> bool:
    if truth is None or predicted is None:
        return truth is None and predicted is None
    return abs((predicted - truth).days) <= DATE_TOLERANCE_DAYS


def score(
    cases: list[GoldenCase],
    predictions: list[Prediction],
    review_threshold: Decimal,
    *,
    bins: int = 10,
) -> EvalReport:
    """골든셋과 모델 출력을 대조해 리포트를 만든다.

    예측이 빠진 케이스는 **평가 대상에서 빼지 않고** ``UNAVAILABLE`` 로 센다 — 러너가 중간에
    뻗었을 때 점수가 조용히 부풀려지는 걸 막는다.

    :raises ValueError: 골든셋에 없는 ``case_id`` 가 섞였거나 같은 케이스가 중복 예측된 경우.
    """
    by_id: dict[str, Prediction] = {}
    known = {c.case_id for c in cases}
    for pred in predictions:
        if pred.case_id not in known:
            raise ValueError(f"골든셋에 없는 case_id 예측입니다: {pred.case_id}")
        if pred.case_id in by_id:
            raise ValueError(f"같은 케이스가 중복 예측되었습니다: {pred.case_id}")
        by_id[pred.case_id] = pred

    confusion: Counter[tuple[Outcome, Outcome]] = Counter()
    latencies: list[float] = []
    calibration: list[tuple[float, bool]] = []
    failures: list[str] = []
    total_cost = Decimal("0")
    weighted_cost = 0
    correct = 0
    amount_hits = 0
    date_hits = 0
    extracted_count = 0
    reviews = 0
    unavailable = 0

    for case in cases:
        truth = case.truth_outcome(review_threshold)
        pred = by_id.get(case.case_id)

        if pred is None or pred.extracted is None:
            predicted = Outcome.UNAVAILABLE
            unavailable += 1
            if pred is not None:
                latencies.append(pred.latency_ms)
                total_cost += pred.cost_usd
            failures.append(
                f"{case.case_id}: 추출 실패 ({pred.error if pred else '예측 누락'})"
            )
        else:
            latencies.append(pred.latency_ms)
            total_cost += pred.cost_usd
            extracted_count += 1
            predicted = decide(
                pred.extracted, case.capture.amount, case.capture.captured_at, review_threshold
            ).outcome

            amount_ok = pred.extracted.total_amount == case.truth_amount
            date_ok = _date_within_tolerance(case.truth_date, pred.extracted.transaction_date)
            amount_hits += int(amount_ok)
            date_hits += int(date_ok)
            calibration.append((float(pred.extracted.confidence), amount_ok and date_ok))

        confusion[(truth, predicted)] += 1
        if predicted is Outcome.NEEDS_REVIEW:
            reviews += 1
        if predicted is truth:
            correct += 1
        else:
            weighted_cost += outcome_cost(truth, predicted)
            if predicted is not Outcome.UNAVAILABLE:
                failures.append(f"{case.case_id}: 정답 {truth.value} → 예측 {predicted.value}")

    n = len(cases)
    return EvalReport(
        n=n,
        accuracy=correct / n if n else 0.0,
        confusion=dict(confusion),
        critical_false_mismatch=confusion[(Outcome.MATCHED, Outcome.MISMATCHED)],
        critical_false_match=confusion[(Outcome.MISMATCHED, Outcome.MATCHED)],
        premature_close=(
            confusion[(Outcome.NEEDS_REVIEW, Outcome.MATCHED)]
            + confusion[(Outcome.NEEDS_REVIEW, Outcome.MISMATCHED)]
        ),
        review_rate=reviews / n if n else 0.0,
        unavailable_rate=unavailable / n if n else 0.0,
        # 분모는 추출에 성공한 건수다 — 실패분은 unavailable_rate 가 따로 들고 있다.
        amount_exact_rate=amount_hits / extracted_count if extracted_count else 0.0,
        date_within_tolerance_rate=date_hits / extracted_count if extracted_count else 0.0,
        ece=expected_calibration_error(calibration, bins=bins),
        latency_p50_ms=percentile(latencies, 50),
        latency_p95_ms=percentile(latencies, 95),
        total_cost_usd=total_cost,
        weighted_cost=weighted_cost,
        failures=failures,
    )
