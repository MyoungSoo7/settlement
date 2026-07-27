"""
layout.py — 클러스터 배치 엔진.

"클러스터"의 추상화
-------------------
이 프로젝트에서 클러스터는 (팔란티어 그림에 국한되지 않고)
  "의미적으로 묶이는 구성요소들이, 중심 주변에 산포되되 서로 겹치지 않는 집합"
으로 정의한다. 따라서 쿠버네티스 노드든, 회사 시스템의 서비스 그룹이든
JSON 설정만 바꾸면 같은 엔진으로 배치된다.

배치 전략: 가우시안 산포 + rejection sampling(겹침 거부).
시드를 고정해 항상 같은 그림이 재현된다(과제 검증 가능성).
"""

from __future__ import annotations

import math
import random

from .shapes import SQUASH, Prism


def _too_close(a: Prism, b: Prism, margin: float = 0.92) -> bool:
    """기둥을 세로로 긴 타원 footprint 로 근사해 겹침 판정."""
    ax_, ay = a.cx, a.cy - a.h / 2
    bx, by = b.cx, b.cy - b.h / 2
    dx = (ax_ - bx)
    dy = (ay - by) * 0.62  # 세로는 조금 더 촘촘해도 허용(원근감)
    dist = math.hypot(dx, dy)
    need = (a.r + b.r) * margin + abs(a.h - b.h) * 0.10
    return dist < need


def place_cluster(
    rng: random.Random,
    members: list[dict],
    center: tuple[float, float],
    spread: float,
    unit: float,
    placed: list[Prism],
) -> list[Prism]:
    """
    한 클러스터의 도형들을 배치한다.

    members 예시: {"shape": "hexagon", "count": 3, "size": [0.7, 1.1],
                   "height": [1.8, 3.0], "accent": 0.4, "label": "API SERVER"}
      - size/height 는 unit 대비 배율 범위, accent 는 파란 윗면 확률
      - label 은 해당 종류의 '첫 번째' 도형에 주석으로 붙는다
    """
    out: list[Prism] = []
    for m in members:
        count = m.get("count", 1)
        smin, smax = m.get("size", [0.55, 0.95])
        hmin, hmax = m.get("height", [1.7, 3.1])
        accent_p = m.get("accent", 0.3)
        for i in range(count):
            for _ in range(400):  # rejection sampling
                r = unit * rng.uniform(smin, smax)
                p = Prism(
                    shape=m["shape"],
                    cx=rng.gauss(center[0], spread),
                    cy=rng.gauss(center[1], spread * 0.72),
                    r=r,
                    h=r * rng.uniform(hmin, hmax),
                    rot=rng.uniform(0, 2 * math.pi),
                    accent=rng.random() < accent_p,
                    label=m.get("label") if i == 0 else None,
                )
                if all(not _too_close(p, q) for q in placed + out):
                    out.append(p)
                    break
            else:
                # 400번 실패 시 마지막 후보라도 채택(설정이 과밀한 경우)
                out.append(p)
    return out


def nearest_links(prisms: list[Prism], k: int = 1) -> list[tuple[Prism, Prism]]:
    """클러스터 내부 연결선: 각 도형을 가장 가까운 이웃과 잇는다(중복 제거)."""
    links: set[tuple[int, int]] = set()
    for i, a in enumerate(prisms):
        dists = sorted(
            (math.hypot(a.cx - b.cx, (a.base_y - b.base_y)), j)
            for j, b in enumerate(prisms) if j != i
        )
        for _, j in dists[:k]:
            links.add((min(i, j), max(i, j)))
    return [(prisms[i], prisms[j]) for i, j in links]


def bridge_link(ca: list[Prism], cb: list[Prism]) -> tuple[Prism, Prism]:
    """클러스터 간 연결선: 두 클러스터에서 가장 가까운 도형 쌍."""
    best, pair = float("inf"), (ca[0], cb[0])
    for a in ca:
        for b in cb:
            d = math.hypot(a.cx - b.cx, a.base_y - b.base_y)
            if d < best:
                best, pair = d, (a, b)
    return pair
