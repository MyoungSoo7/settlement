"""평가 하네스 CLI.

::

    python -m receipt_ocr build --count 40
    python -m receipt_ocr run --provider gemini
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
from decimal import Decimal

from ..synth.generator import generate
from ..synth.renderer import render_to_file
from . import goldenset
from .runner import evaluate, format_report, slice_reports

SERVICE_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent.parent
REPO_ROOT = SERVICE_ROOT.parent

DEFAULT_GOLDENSET = SERVICE_ROOT / "data" / "goldenset.json"
DEFAULT_IMAGES = SERVICE_ROOT / "build" / "images"
DEFAULT_REPORTS = SERVICE_ROOT / "build" / "reports"

#: 운영 기본값과 같아야 한다 — ``app.card.receipt-ocr.review-threshold``.
DEFAULT_REVIEW_THRESHOLD = Decimal("0.80")


def load_repo_env(name: str) -> str:
    """환경변수를 읽되, 없으면 저장소 루트 ``.env`` 에서 찾는다(키가 거기 산다)."""
    value = os.environ.get(name, "")
    if value:
        return value
    env_file = REPO_ROOT / ".env"
    if not env_file.exists():
        return ""
    for line in env_file.read_text(encoding="utf-8", errors="ignore").splitlines():
        stripped = line.strip()
        if stripped.startswith(f"{name}=") and not stripped.startswith("#"):
            return stripped.split("=", 1)[1].strip().strip("'\"")
    return ""


def cmd_build(args: argparse.Namespace) -> int:
    """합성 영수증을 렌더하고 골든셋을 굳힌다."""
    images = pathlib.Path(args.images)
    receipts = generate(args.count, seed=args.seed)
    cases = []
    for receipt in receipts:
        path = render_to_file(receipt, images, seed=args.seed)
        # 상대 경로로 굳힌다 — 절대 경로는 다른 머신에서 그대로 깨진다.
        relative = path.resolve().relative_to(SERVICE_ROOT).as_posix()
        cases.append(goldenset.to_golden_case(receipt, image_path=relative))

    out = pathlib.Path(args.out)
    goldenset.save(cases, out)

    counts: dict[str, int] = {}
    for case in cases:
        key = case.truth_outcome(DEFAULT_REVIEW_THRESHOLD).value
        counts[key] = counts.get(key, 0) + 1

    print(f"골든셋 {len(cases)}건 생성 → {out}")
    print(f"이미지 → {images}")
    print("정답 판정 분포: " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
    return 0


def _build_provider(args: argparse.Namespace):
    if args.provider in ("local", "local-prep"):
        from ..providers.local_ocr import RapidOcrProvider

        return RapidOcrProvider(preprocess=args.provider == "local-prep")
    if args.provider == "gemini":
        from ..providers.gemini import GeminiProvider

        api_key = load_repo_env("GEMINI_API_KEY")
        if not api_key:
            raise SystemExit(
                "GEMINI_API_KEY 를 찾지 못했습니다 (환경변수 또는 저장소 루트 .env)."
            )
        return GeminiProvider(
            api_key,
            model=args.model,
            price_per_mtok_in=Decimal(args.price_in),
            price_per_mtok_out=Decimal(args.price_out),
        )
    raise SystemExit(f"알 수 없는 프로바이더입니다: {args.provider}")


def cmd_run(args: argparse.Namespace) -> int:
    cases = goldenset.load(pathlib.Path(args.goldenset), base_dir=SERVICE_ROOT)
    if args.limit:
        cases = cases[: args.limit]
    provider = _build_provider(args)
    threshold = Decimal(args.threshold)

    print(f"{provider.name} 으로 {len(cases)}건 평가 중 "
          f"(workers={args.workers}, 임계={threshold})...")
    report, predictions = evaluate(
        provider, cases, threshold, workers=args.workers, progress=args.progress
    )

    slices = {
        "시나리오": slice_reports(cases, predictions, threshold, "scenario"),
        "촬영조건": slice_reports(cases, predictions, threshold, "condition"),
    }
    text = format_report(report, provider.name, slices=slices)
    print(text)

    if args.workers > 1:
        print("\n주의: workers>1 이라 지연 수치는 모델 응답시간이 아닙니다(순차로 다시 재세요).")

    if args.save:
        DEFAULT_REPORTS.mkdir(parents=True, exist_ok=True)
        stem = provider.name.replace(":", "_").replace("/", "_")
        (DEFAULT_REPORTS / f"{stem}.txt").write_text(text, encoding="utf-8")
        (DEFAULT_REPORTS / f"{stem}.json").write_text(
            json.dumps(
                {
                    "provider": provider.name,
                    "n": report.n,
                    "accuracy": report.accuracy,
                    "critical_false_mismatch": report.critical_false_mismatch,
                    "critical_false_match": report.critical_false_match,
                    "premature_close": report.premature_close,
                    "review_rate": report.review_rate,
                    "unavailable_rate": report.unavailable_rate,
                    "amount_exact_rate": report.amount_exact_rate,
                    "date_within_tolerance_rate": report.date_within_tolerance_rate,
                    "ece": report.ece,
                    "latency_p50_ms": report.latency_p50_ms,
                    "latency_p95_ms": report.latency_p95_ms,
                    "total_cost_usd": str(report.total_cost_usd),
                    "weighted_cost": report.weighted_cost,
                    "failures": report.failures,
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        print(f"\n리포트 저장 → {DEFAULT_REPORTS}")
    return 0


def cmd_serve(args: argparse.Namespace) -> int:
    """추출 API 를 띄운다 — Phase 3 에서 Java 어댑터가 부를 자리."""
    import uvicorn

    uvicorn.run("receipt_ocr.api.app:app", host=args.host, port=args.port, log_level="info")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="receipt_ocr", description="영수증 OCR 평가 하네스")
    sub = parser.add_subparsers(dest="command", required=True)

    build = sub.add_parser("build", help="합성 골든셋 생성")
    build.add_argument("--count", type=int, default=40)
    build.add_argument("--seed", type=int, default=20260821)
    build.add_argument("--out", default=str(DEFAULT_GOLDENSET))
    build.add_argument("--images", default=str(DEFAULT_IMAGES))
    build.set_defaults(func=cmd_build)

    run = sub.add_parser("run", help="프로바이더 평가")
    run.add_argument("--provider", default="gemini",
                     choices=["gemini", "local", "local-prep"],
                     help="local=자체 호스팅 OCR, local-prep=대비 정규화 전처리 포함")
    run.add_argument("--model", default="gemini-2.5-flash")
    run.add_argument("--goldenset", default=str(DEFAULT_GOLDENSET))
    run.add_argument("--threshold", default=str(DEFAULT_REVIEW_THRESHOLD))
    run.add_argument("--limit", type=int, default=0, help="앞에서 N건만 (스모크용)")
    run.add_argument("--workers", type=int, default=1,
                     help="1 이 아니면 지연 수치는 신뢰할 수 없다")
    run.add_argument("--progress", action="store_true")
    run.add_argument("--save", action="store_true")
    run.add_argument("--price-in", dest="price_in", default="0",
                     help="입력 100만 토큰당 USD (모르면 0 — 지어내지 않는다)")
    run.add_argument("--price-out", dest="price_out", default="0",
                     help="출력 100만 토큰당 USD")
    run.set_defaults(func=cmd_run)

    serve = sub.add_parser("serve", help="추출 API 서빙")
    # 8123 — 폴리글랏 파이썬 대역(8120~8122) 다음 자리.
    serve.add_argument("--port", type=int, default=8123)
    serve.add_argument("--host", default="0.0.0.0")
    serve.set_defaults(func=cmd_serve)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)
