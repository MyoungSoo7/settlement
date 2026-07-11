#!/usr/bin/env python
"""diagnostic-packet.json -> executive-summary.png.

This intentionally uses Pillow instead of free-form image generation: every
number on the snapshot comes from the diagnostic packet, so the visual remains
auditable.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


W, H = 1600, 1000
NAVY = "#13233F"
BLUE = "#2E74B5"
ACCENT = "#D04A02"
AMBER = "#B45309"
GREEN = "#1F7A33"
GRAY = "#667085"
LIGHT = "#F5F7FA"
PANEL = "#FFFFFF"
BORDER = "#D9DEE8"


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    candidates = [
        r"C:\Windows\Fonts\malgunbd.ttf" if bold else r"C:\Windows\Fonts\malgun.ttf",
        r"C:\Windows\Fonts\arialbd.ttf" if bold else r"C:\Windows\Fonts\arial.ttf",
        "/usr/share/fonts/truetype/noto/NotoSansCJK-Bold.ttc" if bold else "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ]
    for candidate in candidates:
        if Path(candidate).exists():
            return ImageFont.truetype(candidate, size)
    return ImageFont.load_default()


F = {
    "title": font(48, True),
    "h1": font(30, True),
    "h2": font(24, True),
    "body": font(22),
    "small": font(18),
    "tiny": font(15),
    "num": font(28, True),
}


def draw_text(draw: ImageDraw.ImageDraw, xy, text: str, fill=NAVY, font_key="body", max_width: int | None = None, line_gap=7):
    if max_width is None:
        draw.text(xy, text, fill=fill, font=F[font_key])
        return xy[1] + F[font_key].size + line_gap
    words = str(text).split()
    lines: list[str] = []
    current = ""
    for word in words:
        trial = f"{current} {word}".strip()
        if draw.textlength(trial, font=F[font_key]) <= max_width or not current:
            current = trial
        else:
            lines.append(current)
            current = word
    if current:
        lines.append(current)
    y = xy[1]
    for line in lines:
        draw.text((xy[0], y), line, fill=fill, font=F[font_key])
        y += F[font_key].size + line_gap
    return y


def rounded(draw, box, fill=PANEL, outline=BORDER, radius=18, width=2):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def compact_title(name: str) -> str:
    return name.replace("(주)", "").replace("주식회사", "").strip()


def signal_label(signal: dict) -> str:
    return {
        "E1": "수익/채권",
        "E2": "재고",
        "E3": "차입/이자",
        "E4": "유동성",
        "E5": "공시 행간",
        "S1": "수익 인식",
        "S2": "채권 집중",
        "S3": "원가 배부",
        "S4": "금리 노출",
    }.get(signal.get("id"), signal.get("name", "")[:10])


def news_categories(packet: dict) -> list[tuple[str, int]]:
    cats = (packet.get("newsSignals") or {}).get("categories") or {}
    rows = []
    for value in cats.values():
        count = int(value.get("count") or 0)
        if count:
            rows.append((value.get("name") or value.get("id") or "뉴스", count))
    return sorted(rows, key=lambda x: x[1], reverse=True)[:6]


def first_action(packet: dict) -> str:
    present = [s for s in packet.get("signals", []) if s.get("present")]
    if present:
        hints = present[0].get("checkHints") or []
        if hints:
            return hints[0]
        return present[0].get("name", "PRESENT 신호 확인")
    advice = ((packet.get("newsSignals") or {}).get("advice") or [])
    if advice:
        return advice[0].get("suggestedAction") or advice[0].get("crossCheck") or "뉴스 신호 교차 확인"
    return "분기별 정기 점검 유지"


def render(packet: dict, out: Path) -> None:
    img = Image.new("RGB", (W, H), "#FFFFFF")
    draw = ImageDraw.Draw(img)

    corp = packet.get("corp") or {}
    name = compact_title(corp.get("name") or corp.get("corpCode") or "Company")
    year = packet.get("year") or "-"
    present = [s for s in packet.get("signals", []) if s.get("present")]
    signal_count = len(present)
    news_total = ((packet.get("newsSignals") or {}).get("totalUniqueItems") or 0)

    draw.rectangle((0, 0, W, 18), fill=ACCENT)
    draw.text((70, 55), f"{name} Executive Snapshot", fill=NAVY, font=F["title"])
    draw.text((72, 118), f"DART {year} CFS | ECOS | Naver News | generated from diagnostic-packet.json", fill=GRAY, font=F["small"])

    # KPI cards
    cards = [
        ("PRESENT", f"{signal_count}", "결정론 신호"),
        ("NEWS", f"{news_total}", "고유 뉴스 메타데이터"),
        ("MACRO", f"{(packet.get('macro') or {}).get('latest', {}).get('value', '-')}", (packet.get("macro") or {}).get("unit", "")),
    ]
    x = 70
    for title, number, caption in cards:
        rounded(draw, (x, 170, x + 450, 310))
        draw.text((x + 28, 195), title, fill=GRAY, font=F["h2"])
        draw.text((x + 28, 232), number, fill=ACCENT if title == "PRESENT" else NAVY, font=F["num"])
        draw.text((x + 125, 240), caption, fill=GRAY, font=F["small"])
        x += 490

    # Signal matrix
    rounded(draw, (70, 350, 760, 875))
    draw.text((100, 380), "DART / 내부 신호 매트릭스", fill=NAVY, font=F["h1"])
    y = 438
    for signal in (packet.get("signals") or [])[:8]:
        active = bool(signal.get("present"))
        color = ACCENT if active else GREEN
        label = signal_label(signal)
        name_txt = signal.get("name", "")
        draw.rounded_rectangle((100, y, 210, y + 42), radius=21, fill=color)
        draw.text((125, y + 8), "주의" if active else "정상", fill="#FFFFFF", font=F["tiny"])
        draw.text((230, y + 6), f"{signal.get('id')} {label}", fill=NAVY, font=F["body"])
        draw.text((430, y + 8), name_txt[:24], fill=GRAY, font=F["small"])
        y += 56

    # News bars
    rounded(draw, (820, 350, 1530, 875))
    draw.text((850, 380), "뉴스 테마", fill=NAVY, font=F["h1"])
    rows = news_categories(packet)
    max_count = max([c for _, c in rows], default=1)
    y = 445
    for label, count in rows:
        short = label.replace("/", " / ")
        draw.text((850, y), short[:22], fill=NAVY, font=F["small"])
        bar_w = int(460 * count / max_count)
        draw.rounded_rectangle((850, y + 28, 850 + bar_w, y + 52), radius=10, fill=BLUE)
        draw.text((1325, y + 18), f"{count}건", fill=ACCENT, font=F["h2"])
        y += 70
    if not rows:
        draw.text((850, 455), "뉴스 신호 없음 또는 미사용", fill=GRAY, font=F["body"])

    # Priority action
    rounded(draw, (70, 905, 1530, 970), fill=LIGHT)
    draw.text((100, 925), "최우선 확인", fill=ACCENT, font=F["h2"])
    draw_text(draw, (275, 926), first_action(packet), fill=NAVY, font_key="body", max_width=1180, line_gap=2)

    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("packet", help="diagnostic-packet.json")
    parser.add_argument("--out", default="executive-summary.png")
    args = parser.parse_args()
    packet = json.loads(Path(args.packet).read_text(encoding="utf-8"))
    render(packet, Path(args.out))
    print(args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
