"""평가 러너 테스트 — "조용히 빠지는 케이스가 없는가" 가 핵심이다.

이미지가 없어서 호출조차 못 한 건이 집계에서 빠지면 정확도가 부풀려진다. 실패는 반드시 실패로
세어져야 하고, 리포트에는 정확도 한 줄이 아니라 치명 오류·리뷰율·단면이 함께 나와야 한다.
"""

from __future__ import annotations

import datetime as _dt
import pathlib
from decimal import Decimal

import pytest

from receipt_ocr.domain.extracted import ExtractedReceipt
from receipt_ocr.domain.matcher import Outcome
from receipt_ocr.eval.runner import (
    _mime_for,
    _predict_one,
    evaluate,
    format_report,
    run_provider,
    slice_reports,
)
from receipt_ocr.eval.scorer import CaptureRef, GoldenCase
from receipt_ocr.providers.base import ExtractionResult

THRESHOLD = Decimal("0.80")
CAPTURED_AT = _dt.datetime(2026, 3, 4, 12, 30, tzinfo=_dt.timezone.utc)
CAPTURED_DATE = _dt.date(2026, 3, 4)


def golden(case_id: str, *, image_path: str | None = None, amount: str = "12300",
           scenario: str = "정상", condition: str = "양호") -> GoldenCase:
    return GoldenCase(
        case_id=case_id,
        capture=CaptureRef(capture_id=f"CAP-{case_id}", amount=Decimal("12300"),
                           captured_at=CAPTURED_AT),
        truth_amount=Decimal(amount),
        truth_date=CAPTURED_DATE,
        image_path=image_path,
        scenario=scenario,
        condition=condition,
    )


class StubProvider:
    """호출을 기록하는 프로바이더. 실패도 예외가 아니라 결과로 표현한다(계약)."""

    def __init__(self, *, error: str = "", amount: str = "12300", confidence: str = "0.95"):
        self.calls: list[tuple[bytes, str]] = []
        self._error = error
        self._amount = amount
        self._confidence = confidence

    @property
    def name(self) -> str:
        return "stub:v1"

    def extract(self, content: bytes, content_type: str) -> ExtractionResult:
        self.calls.append((content, content_type))
        if self._error:
            return ExtractionResult(None, latency_ms=12.0, error=self._error)
        return ExtractionResult(
            ExtractedReceipt(None, CAPTURED_DATE, Decimal(self._amount),
                             Decimal(self._confidence), Decimal(self._confidence)),
            latency_ms=12.0,
            cost_usd=Decimal("0.0001"),
        )


@pytest.fixture()
def image(tmp_path: pathlib.Path) -> pathlib.Path:
    path = tmp_path / "receipt.jpg"
    path.write_bytes(b"\xff\xd8\xff\xd9")
    return path


class TestMimeFor:
    @pytest.mark.parametrize(
        "name,expected",
        [
            ("a.jpg", "image/jpeg"),
            ("a.JPEG", "image/jpeg"),
            ("a.png", "image/png"),
            ("a.webp", "image/webp"),
            # 모르는 확장자는 휴대폰 사진(JPEG)으로 가정한다 — 업로드의 대다수다.
            ("a.heic", "image/jpeg"),
            ("noext", "image/jpeg"),
        ],
    )
    def test_확장자로_MIME_을_고른다(self, name, expected):
        assert _mime_for(pathlib.Path(name)) == expected


class TestPredictOne:
    def test_이미지_경로가_없으면_호출하지_않고_실패로_센다(self):
        provider = StubProvider()
        pred = _predict_one(provider, golden("C1", image_path=None))

        assert pred.extracted is None
        assert "이미지 경로 없음" in pred.error
        assert provider.calls == [], "호출조차 하면 안 된다 — 비용이 든다"

    def test_이미지_파일이_없으면_실패로_센다(self, tmp_path):
        provider = StubProvider()
        pred = _predict_one(provider, golden("C1", image_path=str(tmp_path / "missing.jpg")))

        assert pred.extracted is None
        assert "이미지 파일 없음" in pred.error
        assert provider.calls == []

    def test_성공하면_지연과_비용까지_옮겨_담는다(self, image):
        provider = StubProvider()
        pred = _predict_one(provider, golden("C1", image_path=str(image)))

        assert pred.extracted is not None
        assert pred.latency_ms == 12.0
        assert pred.cost_usd == Decimal("0.0001")
        assert provider.calls == [(b"\xff\xd8\xff\xd9", "image/jpeg")]

    def test_프로바이더_실패는_예외가_아니라_결과로_돌아온다(self, image):
        provider = StubProvider(error="503 rate limited")
        pred = _predict_one(provider, golden("C1", image_path=str(image)))

        assert pred.extracted is None
        assert pred.error == "503 rate limited"


class TestRunProvider:
    def test_순차_실행은_케이스_순서를_보존한다(self, image):
        cases = [golden(f"C{i}", image_path=str(image)) for i in range(3)]
        preds = run_provider(StubProvider(), cases)

        assert [p.case_id for p in preds] == ["C0", "C1", "C2"]

    def test_progress_는_케이스마다_한_줄씩_찍는다(self, image, capsys):
        cases = [golden(f"C{i}", image_path=str(image)) for i in range(2)]
        run_provider(StubProvider(), cases, progress=True)

        out = capsys.readouterr().out
        assert "[1/2] C0" in out
        assert "[2/2] C1" in out

    def test_병렬_실행도_같은_결과를_같은_순서로_낸다(self, image):
        cases = [golden(f"C{i}", image_path=str(image)) for i in range(5)]

        sequential = run_provider(StubProvider(), cases, workers=1)
        parallel = run_provider(StubProvider(), cases, workers=4)

        assert [p.case_id for p in parallel] == [p.case_id for p in sequential]
        assert all(p.extracted is not None for p in parallel)


class TestEvaluate:
    def test_리포트와_예측을_함께_돌려준다(self, image):
        cases = [golden("C1", image_path=str(image)), golden("C2", image_path=str(image))]
        report, preds = evaluate(StubProvider(), cases, THRESHOLD)

        assert report.n == 2
        assert len(preds) == 2

    def test_이미지가_없는_건은_실패율에_잡힌다(self):
        # 조용히 빠지면 정확도가 부풀려진다 — 그게 이 테스트가 막는 것.
        cases = [golden("C1", image_path=None), golden("C2", image_path=None)]
        report, _ = evaluate(StubProvider(), cases, THRESHOLD)

        assert report.n == 2
        assert report.unavailable_rate == 1.0


class TestSliceReports:
    def test_시나리오별로_잘라_다시_채점한다(self, image):
        cases = [
            golden("C1", image_path=str(image), scenario="정상"),
            golden("C2", image_path=str(image), scenario="정상"),
            golden("C3", image_path=str(image), scenario="금액조작"),
        ]
        _, preds = evaluate(StubProvider(), cases, THRESHOLD)

        slices = slice_reports(cases, preds, THRESHOLD, "scenario")

        assert set(slices) == {"정상", "금액조작"}
        assert slices["정상"].n == 2
        assert slices["금액조작"].n == 1

    def test_태그가_비면_없음_으로_묶는다(self, image):
        cases = [golden("C1", image_path=str(image), scenario="")]
        _, preds = evaluate(StubProvider(), cases, THRESHOLD)

        assert set(slice_reports(cases, preds, THRESHOLD, "scenario")) == {"(없음)"}


class TestFormatReport:
    def test_정확도_한_줄만_보여주지_않는다(self, image):
        cases = [golden(f"C{i}", image_path=str(image)) for i in range(3)]
        report, preds = evaluate(StubProvider(), cases, THRESHOLD)

        text = format_report(
            report,
            "stub:v1",
            slices={"시나리오": slice_reports(cases, preds, THRESHOLD, "scenario")},
        )

        for required in ("대사 판정 일치율", "치명 오류", "리뷰 큐 유입률",
                        "ECE", "혼동 행렬", "시나리오별 단면", "stub:v1"):
            assert required in text, f"리포트에 {required} 가 없다"

    def test_실패_상세는_상한을_넘으면_잘리고_남은_건수를_알린다(self):
        # 이미지가 없는 20건 → 전부 실패. 상세는 상위 N건만 찍고 나머지는 개수로 요약한다.
        cases = [golden(f"C{i}", image_path=None) for i in range(20)]
        report, _ = evaluate(StubProvider(), cases, THRESHOLD)

        text = format_report(report, "stub:v1", show_failures=3)

        assert "[실패 상세] 상위 3건" in text
        assert "외 17건" in text

    def test_show_failures_0_이면_실패_상세를_생략한다(self):
        cases = [golden("C1", image_path=None)]
        report, _ = evaluate(StubProvider(), cases, THRESHOLD)

        assert "[실패 상세]" not in format_report(report, "stub:v1", show_failures=0)

    def test_혼동_행렬은_관측된_행만_찍는다(self, image):
        cases = [golden("C1", image_path=str(image))]
        report, _ = evaluate(StubProvider(), cases, THRESHOLD)

        text = format_report(report, "stub:v1")
        # 헤더(열 이름)를 뺀 데이터 행만 센다.
        rows = [
            ln for ln in text.splitlines()
            if any(ln.strip().startswith(o.value) for o in Outcome) and any(ch.isdigit() for ch in ln)
        ]
        assert len(rows) == 1, "관측되지 않은 정답 행까지 찍으면 표가 거짓말을 한다"
        assert rows[0].strip().startswith(Outcome.MATCHED.value)
