"""
main.py — CLI 엔트리포인트.

사용법
------
  python -m src.main --config configs/palantir.json -o out/palantir.png
  python -m src.main --config configs/kubernetes.json -o out/kubernetes.png
  python -m src.main --list-shapes

설계 의도: "그림"이 아니라 "시스템 기술(description)"이 입력이다.
JSON 설정이 곧 도메인 모델(무엇이 클러스터이고, 무엇이 구성요소인가)이며,
렌더러는 그것을 팔란티어 스타일의 라인아트로 번역할 뿐이다.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .renderer import render
from .shapes import SHAPE_REGISTRY


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="클러스터 다이어그램 생성기 — JSON 설정을 라인아트 이미지로 렌더링",
    )
    parser.add_argument("--config", "-c", help="씬 설정 JSON 경로")
    parser.add_argument("--output", "-o", default="out/diagram.png", help="출력 PNG 경로")
    parser.add_argument("--seed", type=int, help="배치 시드 오버라이드(레이아웃 변형 탐색용)")
    parser.add_argument("--list-shapes", action="store_true", help="지원 도형 목록 출력")
    args = parser.parse_args(argv)

    if args.list_shapes:
        for name, (n, smooth) in SHAPE_REGISTRY.items():
            print(f"  {name:<10} 꼭짓점 {n:>2}개 {'(매끈한 실루엣)' if smooth else ''}")
        print("  prism:N    임의의 N각기둥 (예: prism:8)")
        return 0

    if not args.config:
        parser.error("--config 가 필요합니다 (또는 --list-shapes)")

    config = json.loads(Path(args.config).read_text(encoding="utf-8"))
    if args.seed is not None:
        config["seed"] = args.seed

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    render(config, str(out))
    print(f"생성 완료: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
