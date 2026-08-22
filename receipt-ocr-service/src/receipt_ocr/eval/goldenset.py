"""골든셋 — 합성 영수증을 평가 케이스로 바꾸고 디스크에 고정한다.

**왜 파일로 고정하는가**: 모델 A 와 모델 B 를 다른 날 재더라도 같은 셋을 봐야 비교가 성립한다.
생성기가 시드로 결정적이긴 하지만, 생성 코드가 바뀌면 같은 시드도 다른 셋을 만든다. 그래서
비교의 기준선이 되는 셋은 **JSON 으로 굳혀서** 쓴다.

금액은 문자열로 직렬화한다 — JSON number 로 넣는 순간 float 이 되어 원 단위가 흔들린다.
"""

from __future__ import annotations

import datetime as _dt
import json
import pathlib
from dataclasses import replace
from decimal import Decimal

from ..synth.generator import SyntheticReceipt
from .scorer import CaptureRef, GoldenCase

#: 골든셋 파일 포맷 버전 — 필드가 바뀌면 올리고, 옛 파일은 다시 만든다.
SCHEMA_VERSION = 1


def to_golden_case(receipt: SyntheticReceipt, image_path: str | None = None) -> GoldenCase:
    """합성 영수증 → 평가 케이스. 정답 라벨은 **영수증에 인쇄된 값**이다."""
    return GoldenCase(
        case_id=receipt.case_id,
        capture=CaptureRef(
            capture_id=receipt.capture_id,
            amount=receipt.captured_amount,
            captured_at=receipt.captured_at,
        ),
        truth_amount=receipt.printed_total,
        truth_date=receipt.printed_date,
        image_path=image_path,
        note=receipt.note,
        scenario=receipt.scenario.value,
        condition=receipt.condition.value,
    )


def _case_to_dict(case: GoldenCase) -> dict:
    return {
        "case_id": case.case_id,
        "capture": {
            "capture_id": case.capture.capture_id,
            "amount": str(case.capture.amount),
            "captured_at": case.capture.captured_at.isoformat(),
        },
        "truth_amount": str(case.truth_amount),
        "truth_date": case.truth_date.isoformat() if case.truth_date else None,
        "image_path": case.image_path,
        "note": case.note,
        "scenario": case.scenario,
        "condition": case.condition,
    }


def _case_from_dict(raw: dict) -> GoldenCase:
    capture = raw["capture"]
    truth_date = raw.get("truth_date")
    return GoldenCase(
        case_id=raw["case_id"],
        capture=CaptureRef(
            capture_id=capture["capture_id"],
            amount=Decimal(capture["amount"]),
            captured_at=_dt.datetime.fromisoformat(capture["captured_at"]),
        ),
        truth_amount=Decimal(raw["truth_amount"]),
        truth_date=_dt.date.fromisoformat(truth_date) if truth_date else None,
        image_path=raw.get("image_path"),
        note=raw.get("note", ""),
        scenario=raw.get("scenario", ""),
        condition=raw.get("condition", ""),
    )


def save(cases: list[GoldenCase], path: pathlib.Path) -> None:
    """골든셋을 JSON 으로 굳힌다."""
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "schema_version": SCHEMA_VERSION,
        "count": len(cases),
        "cases": [_case_to_dict(case) for case in cases],
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def load(path: pathlib.Path, base_dir: pathlib.Path | None = None) -> list[GoldenCase]:
    """굳혀 둔 골든셋을 읽는다.

    :param base_dir: 상대 이미지 경로의 기준 디렉터리. 골든셋에는 **상대 경로**를 저장한다 —
        절대 경로로 굳히면 다른 사람 머신에서 그대로 깨지고, 그 실패가 조용히 ``UNAVAILABLE``
        로 집계되어 점수만 나빠진다.

    :raises ValueError: 포맷 버전이 다를 때 — 조용히 다른 셋을 읽어 비교를 망치는 걸 막는다.
    """
    payload = json.loads(path.read_text(encoding="utf-8"))
    version = payload.get("schema_version")
    if version != SCHEMA_VERSION:
        raise ValueError(
            f"골든셋 포맷 버전이 다릅니다: 파일={version}, 기대={SCHEMA_VERSION} — 다시 생성하세요."
        )
    cases = [_case_from_dict(raw) for raw in payload["cases"]]
    if base_dir is None:
        return cases
    return [
        case if not case.image_path or pathlib.Path(case.image_path).is_absolute()
        else replace(case, image_path=str((base_dir / case.image_path).resolve()))
        for case in cases
    ]
