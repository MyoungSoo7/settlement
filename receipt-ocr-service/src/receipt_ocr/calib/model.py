"""교정 모델 — 판독 특징에서 "이 판독이 맞을 확률"로.

**학습은 무겁게, 서빙은 가볍게.** 계수를 JSON 으로 내보내고 서빙은 시그모이드 하나만 계산한다.
운영 이미지에 scikit-learn·numpy 를 넣지 않기 위해서다(모델 파일 하나면 재현된다).

축이 둘이라 모델도 둘이다. 총액과 거래일은 다른 영역에서 읽히고 뭉개지는 이유도 다르다 —
하나로 합치면 이 프로젝트가 없애려던 결함이 학습 단계에서 되살아난다.
"""

from __future__ import annotations

import json
import math
import pathlib
from dataclasses import dataclass

#: 모델 파일 포맷 버전.
SCHEMA_VERSION = 1


@dataclass(frozen=True)
class Head:
    """축 하나의 로지스틱 회귀 — ``sigmoid(b + Σ wᵢxᵢ)``."""

    feature_names: tuple[str, ...]
    weights: tuple[float, ...]
    bias: float
    #: 학습 표본 수·정답 비율 — 모델을 믿어도 되는지 판단할 근거를 같이 남긴다.
    trained_on: int = 0
    positive_rate: float = 0.0

    def probability(self, features: dict[str, float]) -> float:
        z = self.bias + sum(
            w * float(features[name]) for name, w in zip(self.feature_names, self.weights)
        )
        # overflow 방어 — 극단 z 에서 math.exp 가 터진다.
        if z >= 0:
            return 1.0 / (1.0 + math.exp(-min(z, 60.0)))
        exp_z = math.exp(max(z, -60.0))
        return exp_z / (1.0 + exp_z)


@dataclass(frozen=True)
class CalibrationModel:
    amount: Head
    date: Head

    def to_json(self) -> str:
        return json.dumps(
            {
                "schema_version": SCHEMA_VERSION,
                "heads": {
                    name: {
                        "feature_names": list(head.feature_names),
                        "weights": list(head.weights),
                        "bias": head.bias,
                        "trained_on": head.trained_on,
                        "positive_rate": head.positive_rate,
                    }
                    for name, head in (("amount", self.amount), ("date", self.date))
                },
            },
            ensure_ascii=False,
            indent=2,
        )

    @staticmethod
    def from_json(text: str) -> "CalibrationModel":
        payload = json.loads(text)
        if payload.get("schema_version") != SCHEMA_VERSION:
            raise ValueError(
                f"교정 모델 포맷 버전이 다릅니다: 파일={payload.get('schema_version')}, "
                f"기대={SCHEMA_VERSION} — 다시 학습하세요."
            )
        heads = {}
        for name in ("amount", "date"):
            raw = payload["heads"][name]
            heads[name] = Head(
                feature_names=tuple(raw["feature_names"]),
                weights=tuple(float(w) for w in raw["weights"]),
                bias=float(raw["bias"]),
                trained_on=int(raw.get("trained_on", 0)),
                positive_rate=float(raw.get("positive_rate", 0.0)),
            )
        return CalibrationModel(amount=heads["amount"], date=heads["date"])

    def save(self, path: pathlib.Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(self.to_json(), encoding="utf-8")

    @staticmethod
    def load(path: pathlib.Path) -> "CalibrationModel | None":
        """없으면 None — 교정 모델은 선택 사항이고, 없으면 원점수를 그대로 쓴다."""
        if not path.exists():
            return None
        return CalibrationModel.from_json(path.read_text(encoding="utf-8"))
