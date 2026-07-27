"""
shapes.py — 3D 기둥(prism) 도형을 2D 라인아트로 그리는 모듈.

핵심 설계
---------
- 모든 도형은 "윗면 다각형(top polygon)"으로 정의된다.
  * N각기둥 = 정N각형 윗면
  * 원기둥  = 점을 촘촘히 찍은 다각형(smooth=True → 내부 세로선 생략)
- 몸통 실루엣은 (윗면 꼭짓점 + 아랫면 꼭짓점)의 convex hull 로 계산한다.
  → 어떤 볼록 다각형 기둥이든 동일한 코드로 그려지므로,
    새 도형 추가 = SHAPE_REGISTRY 에 한 줄 등록이면 끝난다.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np
from matplotlib.axes import Axes
from matplotlib.patches import Ellipse, Polygon

# ── 스타일 상수 ──────────────────────────────────────────────
LINE_COLOR = "#3a3f44"      # 외곽선
LINE_W = 1.15
INNER_W = 0.85              # 내부 세로 모서리선
FILL = "#ffffff"            # 면 색 (라인아트 흰색)
ACCENT_FILL = "#c9e4f5"     # 윗면 포인트 색(예시 이미지의 옅은 파랑)
SHADOW = (0, 0, 0, 0.055)   # 바닥 그림자
SQUASH = 0.42               # 윗면 y축 압축 비율(원근감)


@dataclass
class Prism:
    """캔버스에 배치된 기둥 하나. cx, cy 는 '윗면 중심' 좌표."""
    shape: str
    cx: float
    cy: float
    r: float                 # 윗면 반지름
    h: float                 # 기둥 높이
    rot: float = 0.0         # 윗면 회전(rad)
    accent: bool = False     # 윗면에 파란 포인트를 넣을지
    label: str | None = None # 개별 도형 주석(annotation) 텍스트

    # 렌더링 후 계산되는 값들
    top_pts: np.ndarray = field(default=None, repr=False)

    @property
    def base_y(self) -> float:
        return self.cy - self.h

    @property
    def anchor(self) -> tuple[float, float]:
        """연결선(dashed link)이 닿는 지점 — 기둥 바닥 중심."""
        return (self.cx, self.base_y - SQUASH * self.r * 0.6)


# ── Shape Registry ───────────────────────────────────────────
# 이름 → (윗면 꼭짓점 수, smooth 여부). 새 도형은 여기 한 줄만 추가.
SHAPE_REGISTRY: dict[str, tuple[int, bool]] = {
    "triangle": (3, False),   # 삼각기둥
    "square":   (4, False),   # 사각기둥
    "pentagon": (5, False),   # 오각기둥
    "hexagon":  (6, False),   # 육각기둥
    "cylinder": (64, True),   # 원기둥(다각형 근사 + 매끈한 실루엣)
}


def resolve_shape(name: str) -> tuple[int, bool]:
    """
    도형 이름 → (꼭짓점 수, smooth).
    "prism:N" 문법으로 등록 없이도 임의의 N각기둥을 지원한다. (예: "prism:8")
    """
    if name in SHAPE_REGISTRY:
        return SHAPE_REGISTRY[name]
    if name.startswith("prism:"):
        n = int(name.split(":", 1)[1])
        if n < 3:
            raise ValueError(f"prism:N 은 N>=3 이어야 합니다: {name}")
        return (n, n >= 24)  # 꼭짓점이 아주 많으면 원기둥처럼 매끈하게
    raise KeyError(
        f"알 수 없는 도형 '{name}'. 사용 가능: {list(SHAPE_REGISTRY)} 또는 'prism:N'"
    )


def _top_polygon(p: Prism) -> np.ndarray:
    """윗면 다각형 좌표 (y축을 SQUASH 만큼 압축해 원근감 표현)."""
    n, _ = resolve_shape(p.shape)
    ang = np.linspace(0, 2 * math.pi, n, endpoint=False) + p.rot + math.pi / 2
    xs = p.cx + p.r * np.cos(ang)
    ys = p.cy + SQUASH * p.r * np.sin(ang)
    return np.column_stack([xs, ys])


def _convex_hull(pts: np.ndarray) -> np.ndarray:
    """Andrew's monotone chain. 의존성 없이 실루엣 계산."""
    pts = np.unique(pts, axis=0)
    pts = pts[np.lexsort((pts[:, 1], pts[:, 0]))]
    if len(pts) <= 2:
        return pts

    def half(points):
        chain = []
        for pt in points:
            while len(chain) >= 2 and np.cross(chain[-1] - chain[-2], pt - chain[-2]) <= 0:
                chain.pop()
            chain.append(pt)
        return chain

    lower, upper = half(pts), half(pts[::-1])
    return np.array(lower[:-1] + upper[:-1])


def draw_prism(ax: Axes, p: Prism) -> None:
    """기둥 하나를 그린다: 그림자 → 몸통 실루엣 → 내부 모서리 → 윗면."""
    top = _top_polygon(p)
    bottom = top - [0, p.h]
    p.top_pts = top

    # 1) 바닥 그림자
    ax.add_patch(Ellipse(
        (p.cx, p.base_y - SQUASH * p.r * 0.55), 2.5 * p.r, 0.85 * SQUASH * p.r,
        facecolor=SHADOW, edgecolor="none", zorder=p.zorder - 0.2,
    ))

    # 2) 몸통: 위/아래 꼭짓점 전체의 convex hull = 실루엣
    hull = _convex_hull(np.vstack([top, bottom]))
    ax.add_patch(Polygon(hull, closed=True, facecolor=FILL,
                         edgecolor=LINE_COLOR, linewidth=LINE_W,
                         joinstyle="round", zorder=p.zorder))

    # 3) 앞쪽(화면 아래 방향) 꼭짓점의 세로 모서리 — 각기둥의 입체감
    _, smooth = resolve_shape(p.shape)
    if not smooth:
        for (x, y) in top:
            if y < p.cy - 1e-9:  # squash 후 y<cy 인 꼭짓점이 앞면
                ax.plot([x, x], [y, y - p.h], color=LINE_COLOR,
                        linewidth=INNER_W, solid_capstyle="round",
                        zorder=p.zorder + 0.1)

    # 4) 윗면
    ax.add_patch(Polygon(top, closed=True, facecolor=FILL,
                         edgecolor=LINE_COLOR, linewidth=LINE_W,
                         joinstyle="round", zorder=p.zorder + 0.2))

    # 5) 윗면 포인트(옅은 파랑 웅덩이) — 예시 이미지의 디테일 재현
    #    다각형 밖으로 나가지 않도록 '내접원 반지름' 기준으로 크기를 잡는다.
    if p.accent:
        n, _ = resolve_shape(p.shape)
        in_r = p.r * math.cos(math.pi / n)  # 정N각형의 내접원 반지름
        ax.add_patch(Ellipse(
            (p.cx + 0.1 * in_r, p.cy - 0.04 * in_r),
            1.35 * in_r, 1.25 * SQUASH * in_r,
            facecolor=ACCENT_FILL, edgecolor="none", zorder=p.zorder + 0.3,
        ))


# zorder 는 렌더러가 깊이 정렬 후 주입한다.
Prism.zorder = 1.0
