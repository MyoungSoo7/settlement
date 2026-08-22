"""교정 모델 학습 — **학습셋에서만** 한다.

.. danger::
   홀드아웃(``data/goldenset.json``)으로 학습하면 이후 모든 수치가 거짓이 된다. 이 모듈은
   학습셋 경로를 인자로 받고, 기본값도 학습셋이다. 홀드아웃은 **마지막에 한 번** 평가에만 쓴다.

표본이 수백 건 규모라 규제를 세게 건다(``C`` 작게). 특징이 6개뿐이라도 100건대에서는 과적합이
쉽고, 과적합된 교정은 원점수보다 나쁘다 — 틀린 확신을 더 정교하게 주장하기 때문이다.
"""

from __future__ import annotations

import pathlib

from ..providers.parsing import ParseFailed, choose_pass, parse_receipt
from . import cache
from .features import (
    AMOUNT_FEATURES,
    DATE_FEATURES,
    Sample,
    amount_features,
    date_features,
    vector,
)
from .model import CalibrationModel, Head

#: 거래일 정답 허용 오차 — 매처와 같은 ±1일.
from ..domain.matcher import DATE_TOLERANCE_DAYS


def collect(cases, cache_dir: pathlib.Path) -> list[Sample]:
    """캐시된 두 패스를 중재해 판독을 만들고, 정답과 대조해 표본을 만든다."""
    samples: list[Sample] = []
    for case in cases:
        cached = cache.load(cache_dir, case.case_id)
        if cached is None:
            continue

        parsed_passes = []
        for name in cache.PASSES:
            try:
                parsed_passes.append((name, parse_receipt(cached[name])))
            except ParseFailed:
                continue
        if not parsed_passes:
            continue

        chosen = choose_pass([p for _, p in parsed_passes])
        # 특징은 채택된 판독을 만든 줄들에서 뽑는다. 중재로 합쳐진 경우 총액 기준 패스를 쓴다.
        lines = next(
            (cached[name] for name, p in parsed_passes
             if p.amount_source == chosen.amount_source
             and p.amount_method == chosen.amount_method),
            cached["raw"],
        )

        amount_ok = chosen.extracted.total_amount == case.truth_amount
        predicted_date = chosen.extracted.transaction_date
        if predicted_date is None or case.truth_date is None:
            date_ok = predicted_date is None and case.truth_date is None
        else:
            date_ok = abs((predicted_date - case.truth_date).days) <= DATE_TOLERANCE_DAYS

        samples.append(
            Sample(
                case_id=case.case_id,
                amount=amount_features(chosen, lines),
                date=date_features(chosen, lines),
                amount_correct=amount_ok,
                date_correct=None if chosen.date_confidence is None else date_ok,
                scenario=case.scenario,
                condition=case.condition,
            )
        )
    return samples


def _fit(rows: list[list[float]], labels: list[int], names: tuple[str, ...],
         *, regularization: float) -> Head:
    """로지스틱 회귀 하나. 표본이 한쪽으로만 쏠리면 학습하지 않고 상수 헤드를 돌려준다."""
    from sklearn.linear_model import LogisticRegression

    positives = sum(labels)
    if not rows or positives == 0 or positives == len(labels):
        # 한 클래스만 있으면 회귀가 성립하지 않는다. 관측된 비율을 그대로 내는 상수 헤드 —
        # 지어낸 계수보다 정직하다.
        rate = (positives / len(labels)) if labels else 0.5
        rate = min(max(rate, 0.01), 0.99)
        import math

        return Head(names, tuple(0.0 for _ in names), math.log(rate / (1 - rate)),
                    trained_on=len(labels), positive_rate=rate)

    model = LogisticRegression(C=regularization, max_iter=2000, solver="lbfgs")
    model.fit(rows, labels)
    return Head(
        feature_names=names,
        weights=tuple(float(w) for w in model.coef_[0]),
        bias=float(model.intercept_[0]),
        trained_on=len(labels),
        positive_rate=positives / len(labels),
    )


def train(samples: list[Sample], *, regularization: float = 0.3) -> CalibrationModel:
    amount_rows = [vector(s.amount, AMOUNT_FEATURES) for s in samples]
    amount_labels = [1 if s.amount_correct else 0 for s in samples]

    dated = [s for s in samples if s.date is not None and s.date_correct is not None]
    date_rows = [vector(s.date, DATE_FEATURES) for s in dated]
    date_labels = [1 if s.date_correct else 0 for s in dated]

    return CalibrationModel(
        amount=_fit(amount_rows, amount_labels, AMOUNT_FEATURES, regularization=regularization),
        date=_fit(date_rows, date_labels, DATE_FEATURES, regularization=regularization),
    )
