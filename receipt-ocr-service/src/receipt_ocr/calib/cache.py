"""OCR 출력 캐시 — 같은 이미지를 몇 번이고 다시 읽지 않기 위한 것.

이미지 한 장 OCR 이 CPU 에서 2~12초다. 파싱 규칙이나 교정 모델을 손볼 때마다 전체 셋을 다시
읽으면 실험 한 번에 10분이 넘게 걸리고, 그러면 실험을 덜 하게 된다 — 그게 진짜 비용이다.

OCR 은 **결정적**이라(같은 이미지 → 같은 박스·점수) 캐시해도 결과가 달라지지 않는다. 원본과
전처리본 **두 패스를 모두** 저장한다(다중 패스 중재가 둘 다 필요하다).

.. warning::
   캐시는 이미지에만 의존한다. 파서·교정 모델을 바꿔도 캐시는 유효하지만, **렌더러나 골든셋을
   다시 만들면 무효**다. 그때는 지우고 다시 만들어야 한다 — 안 그러면 옛 이미지의 판독으로
   새 정답을 채점하게 된다.
"""

from __future__ import annotations

import json
import pathlib

from ..providers.local_ocr import _autocontrast, to_lines
from ..providers.parsing import OcrLine

#: 캐시 포맷 버전 — 저장 형태가 바뀌면 올리고 캐시를 버린다.
SCHEMA_VERSION = 1

PASSES = ("raw", "prep")


def _serialize(lines: list[OcrLine]) -> list[dict]:
    return [
        {"text": ln.text, "confidence": ln.confidence, "height": ln.height, "top": ln.top}
        for ln in lines
    ]


def _deserialize(raw: list[dict]) -> list[OcrLine]:
    return [
        OcrLine(text=d["text"], confidence=d["confidence"], height=d["height"], top=d["top"])
        for d in raw
    ]


def load(cache_dir: pathlib.Path, case_id: str) -> dict[str, list[OcrLine]] | None:
    """캐시된 두 패스를 읽는다. 없거나 버전이 다르면 None."""
    path = cache_dir / f"{case_id}.json"
    if not path.exists():
        return None
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return None
    if payload.get("schema_version") != SCHEMA_VERSION:
        return None
    return {name: _deserialize(payload["passes"][name]) for name in PASSES}


def build(cases, cache_dir: pathlib.Path, *, engine=None, progress: bool = True) -> int:
    """골든셋 전체를 OCR 해 캐시한다. 이미 있는 건은 건너뛴다.

    :returns: 이번에 새로 읽은 건수.
    """
    cache_dir.mkdir(parents=True, exist_ok=True)
    if engine is None:
        from rapidocr_onnxruntime import RapidOCR

        engine = RapidOCR()

    read = 0
    for index, case in enumerate(cases, start=1):
        path = cache_dir / f"{case.case_id}.json"
        if path.exists() and load(cache_dir, case.case_id) is not None:
            continue
        image = pathlib.Path(case.image_path)
        if not image.exists():
            if progress:
                print(f"  [{index}/{len(cases)}] {case.case_id} 이미지 없음 — 건너뜀", flush=True)
            continue

        content = image.read_bytes()
        passes = {}
        for name, data in (("raw", content), ("prep", _autocontrast(content))):
            raw, _ = engine(data)
            passes[name] = _serialize(to_lines(raw))

        path.write_text(
            json.dumps({"schema_version": SCHEMA_VERSION, "case_id": case.case_id,
                        "passes": passes}, ensure_ascii=False),
            encoding="utf-8",
        )
        read += 1
        if progress and index % 10 == 0:
            print(f"  [{index}/{len(cases)}] 캐시 적재 중...", flush=True)
    return read
