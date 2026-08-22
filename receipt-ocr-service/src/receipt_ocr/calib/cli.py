"""교정 파이프라인 CLI — 캐시 → 학습 → 임계 선정 → (마지막에) 홀드아웃 1회.

절차를 코드로 못박아 둔 이유: 이 순서를 사람이 기억에 의존해 지키면 언젠가 홀드아웃으로 임계를
고르게 되고, 그 순간 이후의 모든 수치가 일반화 추정치가 아니게 된다. **어긋난 사용을 막는
장치는 잔소리가 아니라 명령어 구조여야 한다.**

::

    python -m receipt_ocr calibrate cache   --dataset data/trainset.json
    python -m receipt_ocr calibrate fit     --dataset data/trainset.json
    python -m receipt_ocr calibrate holdout --dataset data/goldenset.json
"""

from __future__ import annotations

import json
import pathlib
from decimal import Decimal

from ..eval import goldenset
from ..eval.runner import format_report, slice_reports
from ..eval.scorer import Prediction, score
from ..providers.parsing import ParseFailed, choose_pass, parse_receipt
from . import cache
from .apply import calibrate
from .model import CalibrationModel
from .sweep import best, default_grid, sweep
from .train import collect, train

SERVICE_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent.parent
MODEL_PATH = SERVICE_ROOT / "data" / "calibration.json"
POLICY_PATH = SERVICE_ROOT / "data" / "calibration-policy.json"


def _cache_dir(dataset: pathlib.Path) -> pathlib.Path:
    """학습셋과 홀드아웃의 캐시를 분리한다 — 섞이면 어느 셋을 읽었는지 알 수 없다."""
    name = "holdout" if dataset.stem == "goldenset" else dataset.stem.replace("set", "")
    return SERVICE_ROOT / "build" / "ocr-cache" / name


def _predictions(cases, cache_dir: pathlib.Path,
                 model: CalibrationModel | None) -> list[Prediction]:
    """캐시된 두 패스를 중재하고 (있으면) 교정을 적용해 예측을 만든다."""
    predictions: list[Prediction] = []
    for case in cases:
        cached = cache.load(cache_dir, case.case_id)
        if cached is None:
            predictions.append(Prediction(case.case_id, None, error="OCR 캐시 없음"))
            continue

        passes = []
        for name in cache.PASSES:
            try:
                passes.append((parse_receipt(cached[name]), cached[name]))
            except ParseFailed:
                continue
        if not passes:
            predictions.append(Prediction(case.case_id, None, error="ParseFailed: 총액 후보 없음"))
            continue

        chosen = choose_pass([p for p, _ in passes])
        lines = next(
            (ln for p, ln in passes
             if p.amount_source == chosen.amount_source and p.amount_method == chosen.amount_method),
            passes[0][1],
        )
        chosen = calibrate(chosen, lines, model)
        predictions.append(Prediction(case.case_id, chosen.extracted))
    return predictions


def cmd_cache(args) -> int:
    dataset = pathlib.Path(args.dataset)
    cases = goldenset.load(dataset, base_dir=SERVICE_ROOT)
    directory = _cache_dir(dataset)
    read = cache.build(cases, directory)
    print(f"{dataset.name}: {len(cases)}건 중 {read}건 새로 읽음 → {directory}")
    return 0


def cmd_fit(args) -> int:
    """**학습셋에서만** 학습하고 임계까지 고른다."""
    dataset = pathlib.Path(args.dataset)
    if dataset.stem == "goldenset":
        raise SystemExit(
            "홀드아웃(goldenset)으로는 학습하지 않습니다. 학습셋을 지정하세요 — "
            "여기서 학습하면 이후 모든 수치가 일반화 추정치가 아니게 됩니다."
        )

    cases = goldenset.load(dataset, base_dir=SERVICE_ROOT)
    cache_dir = _cache_dir(dataset)

    samples = collect(cases, cache_dir)
    if not samples:
        raise SystemExit(f"표본이 없습니다 — 먼저 `calibrate cache --dataset {dataset}` 를 돌리세요.")

    model = train(samples, regularization=float(args.regularization))
    model.save(MODEL_PATH)

    predictions = _predictions(cases, cache_dir, model)
    points = sweep(cases, predictions, default_grid(args.step))
    chosen = best(points)
    POLICY_PATH.write_text(
        json.dumps({"review_threshold": str(chosen.threshold),
                    "chosen_on": dataset.name,
                    "weighted_cost": chosen.weighted_cost}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print(f"표본 {len(samples)}건 — 총액 정답률 "
          f"{sum(s.amount_correct for s in samples) / len(samples) * 100:.1f}%")
    print(f"모델 저장 → {MODEL_PATH}")
    print(f"\n[임계 탐색] 학습셋 기준 — 홀드아웃이 아니다")
    for point in points:
        marker = "  ←" if point.threshold == chosen.threshold else ""
        print(f"  {point}{marker}")
    print(f"\n선택된 임계: {chosen.threshold} → {POLICY_PATH.name}")
    return 0


def cmd_holdout(args) -> int:
    """홀드아웃 평가 — **한 번만** 돌린다. 여기 결과로 무언가를 고치면 홀드아웃이 아니게 된다."""
    dataset = pathlib.Path(args.dataset)
    cases = goldenset.load(dataset, base_dir=SERVICE_ROOT)
    cache_dir = _cache_dir(dataset)

    model = CalibrationModel.load(MODEL_PATH) if not args.uncalibrated else None
    threshold = Decimal(args.threshold) if args.threshold else _policy_threshold()

    predictions = _predictions(cases, cache_dir, model)
    report = score(cases, predictions, threshold)
    label = f"local+multipass{'+calib' if model else ''} @임계 {threshold}"
    print(format_report(report, label, slices={
        "시나리오": slice_reports(cases, predictions, threshold, "scenario"),
        "촬영조건": slice_reports(cases, predictions, threshold, "condition"),
    }))
    return 0


def _policy_threshold() -> Decimal:
    if POLICY_PATH.exists():
        return Decimal(json.loads(POLICY_PATH.read_text(encoding="utf-8"))["review_threshold"])
    return Decimal("0.80")


def register(subparsers) -> None:
    parser = subparsers.add_parser("calibrate", help="신뢰도 교정 파이프라인")
    inner = parser.add_subparsers(dest="calib_command", required=True)

    cache_cmd = inner.add_parser("cache", help="OCR 두 패스를 캐시한다(느림, 1회)")
    cache_cmd.add_argument("--dataset", default=str(SERVICE_ROOT / "data" / "trainset.json"))
    cache_cmd.set_defaults(func=cmd_cache)

    fit = inner.add_parser("fit", help="학습셋에서 교정 모델 학습 + 임계 선정")
    fit.add_argument("--dataset", default=str(SERVICE_ROOT / "data" / "trainset.json"))
    fit.add_argument("--regularization", default="0.3", help="작을수록 강한 규제")
    fit.add_argument("--step", default="0.05")
    fit.set_defaults(func=cmd_fit)

    holdout = inner.add_parser("holdout", help="홀드아웃 1회 평가")
    holdout.add_argument("--dataset", default=str(SERVICE_ROOT / "data" / "goldenset.json"))
    holdout.add_argument("--threshold", default="", help="비우면 학습셋에서 고른 임계를 쓴다")
    holdout.add_argument("--uncalibrated", action="store_true", help="교정 없이(대조군)")
    holdout.set_defaults(func=cmd_holdout)
