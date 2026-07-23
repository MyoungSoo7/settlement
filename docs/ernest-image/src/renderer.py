"""
renderer.py — 씬 설정(dict)을 받아 최종 이미지를 합성한다.

렌더링 순서(뒤 → 앞):
  배경 그라데이션 → 클러스터 간/내 점선 연결 → 기둥(깊이 정렬) → 주석 텍스트
깊이 정렬: 화면에서 아래(base_y 가 작은)에 있는 기둥이 앞에 오도록 zorder 부여.
"""

from __future__ import annotations

import random

import matplotlib
matplotlib.use("Agg")
import matplotlib.patheffects as pe
import matplotlib.pyplot as plt
import numpy as np

from .layout import bridge_link, nearest_links, place_cluster
from .shapes import Prism, draw_prism

LINK_STYLE = dict(color="#b6bdc4", linewidth=0.95, linestyle=(0, (4, 4)), zorder=0.3)
# 흰색 헤일로(path effect)로 도형·연결선 위에서도 라벨 가독성을 확보한다.
TEXT_STYLE = dict(color="#4a5056", fontsize=10.5, fontweight="medium",
                  fontfamily="DejaVu Sans", zorder=50,
                  path_effects=[pe.withStroke(linewidth=3.2, foreground="#ffffff")])

# 주석 방향 → (화살표 글리프, 텍스트 오프셋 배율, 정렬)
_DIRS = {
    "right":      ("\u2190 ", (1.0, 0.15), "left"),    # ← TEXT (도형 오른쪽)
    "left":       (" \u2192", (-1.0, 0.15), "right"),  # TEXT → (도형 왼쪽)
    "upper-right": ("\u2199 ", (0.75, 1.0), "left"),   # ↙ TEXT (도형 위-오른쪽)
    "upper-left":  (" \u2198", (-0.75, 1.0), "right"), # TEXT ↘ (도형 위-왼쪽)
}


def _annotate(ax, p: Prism, text: str, direction: str, unit: float):
    glyph, (ox, oy), ha = _DIRS[direction]
    x = p.cx + ox * (p.r + 0.42 * unit)
    y = p.cy + oy * (0.55 * unit)
    label = glyph + text if ha == "left" else text + glyph
    ax.text(x, y, label, ha=ha, va="center", **TEXT_STYLE)


def _auto_direction(p: Prism, w: float, h: float) -> str:
    horiz = "right" if p.cx < w * 0.55 else "left"
    return ("upper-" + horiz) if p.cy < h * 0.45 else horiz


def render(config: dict, out_path: str) -> None:
    W, H = config.get("canvas", [16.0, 9.0])
    unit = config.get("unit", 0.55)              # 도형 크기의 기준 단위
    rng = random.Random(config.get("seed", 7))

    fig, ax = plt.subplots(figsize=(W, H), dpi=config.get("dpi", 170))
    ax.set_xlim(0, W); ax.set_ylim(0, H)
    ax.set_aspect("equal"); ax.axis("off")
    fig.subplots_adjust(left=0, right=1, top=1, bottom=0)

    # 배경: 흰색 → 아주 옅은 회청색의 세로 그라데이션
    grad = np.linspace(0, 1, 256).reshape(-1, 1)
    ax.imshow(grad, extent=[0, W, 0, H], origin="lower", aspect="auto",
              cmap=matplotlib.colors.LinearSegmentedColormap.from_list(
                  "bg", ["#eef1f4", "#ffffff"]), zorder=-1)

    # 1) 클러스터별 배치
    clusters: dict[str, list[Prism]] = {}
    placed: list[Prism] = []
    for c in config["clusters"]:
        cx, cy = c["center"]
        members = place_cluster(
            rng, c["members"],
            center=(cx * W, cy * H),
            spread=c.get("spread", 0.09) * W,
            unit=unit, placed=placed,
        )
        clusters[c["id"]] = members
        placed += members

    # 2) 연결선 (도형보다 아래 zorder)
    def _draw_link(a: Prism, b: Prism):
        (x1, y1), (x2, y2) = a.anchor, b.anchor
        ax.plot([x1, x2], [y1, y2], **LINK_STYLE)

    for c in config["clusters"]:
        if c.get("intra_links", True):
            for a, b in nearest_links(clusters[c["id"]]):
                _draw_link(a, b)
    for ida, idb in config.get("links", []):
        _draw_link(*bridge_link(clusters[ida], clusters[idb]))

    # 3) 기둥 렌더링 — 깊이 정렬(뒤에 있는 것부터)
    for z, p in enumerate(sorted(placed, key=lambda p: -p.base_y)):
        p.zorder = 1.0 + z
        draw_prism(ax, p)

    # 4) 주석: (a) member 별 label, (b) 클러스터 label
    for c in config["clusters"]:
        members = clusters[c["id"]]
        for p in members:
            if p.label:
                _annotate(ax, p, p.label,
                          _auto_direction(p, W, H), unit)
        if c.get("label"):
            # 클러스터 대표(가장 큰 도형)에 라벨을 단다
            rep = max(members, key=lambda p: p.r)
            if not rep.label:  # 개별 라벨과 중복 방지
                _annotate(ax, rep, c["label"],
                          c.get("label_dir") or _auto_direction(rep, W, H), unit)

    if config.get("title"):
        ax.text(W / 2, H - 0.45, config["title"], ha="center", va="top",
                color="#33383d", fontsize=15, fontweight="bold",
                fontfamily="DejaVu Sans", zorder=60)

    fig.savefig(out_path, facecolor="#ffffff")
    plt.close(fig)
