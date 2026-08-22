"""평가 CLI 테스트.

CLI 는 "절차를 코드로 못박은 것"이라 여기서 깨지면 사람이 손으로 잘못된 순서를 밟게 된다.
그래서 각 서브커맨드가 **무엇을 만들고 무엇을 거부하는지**를 고정한다. 실제 OCR·LLM 은 부르지
않는다 — 부르면 테스트가 느려지고 외부 가용성에 매달린다.
"""

from __future__ import annotations

import datetime as _dt
import json
import pathlib
from decimal import Decimal

import pytest

from receipt_ocr.domain.extracted import ExtractedReceipt
from receipt_ocr.eval import cli
from receipt_ocr.eval.scorer import CaptureRef, GoldenCase
from receipt_ocr.providers.base import ExtractionResult

CAPTURED_AT = _dt.datetime(2026, 3, 4, 12, 30, tzinfo=_dt.timezone.utc)
CAPTURED_DATE = _dt.date(2026, 3, 4)


class StubProvider:
    def __init__(self):
        self.calls = 0

    @property
    def name(self) -> str:
        return "stub:v1"

    def extract(self, content: bytes, content_type: str) -> ExtractionResult:
        self.calls += 1
        return ExtractionResult(
            ExtractedReceipt(None, CAPTURED_DATE, Decimal("12300"),
                             Decimal("0.95"), Decimal("0.95")),
            latency_ms=10.0,
        )


@pytest.fixture()
def goldenset_file(tmp_path: pathlib.Path) -> pathlib.Path:
    """이미지가 실제로 존재하는 2건짜리 골든셋."""
    from receipt_ocr.eval import goldenset

    image = tmp_path / "img.jpg"
    image.write_bytes(b"\xff\xd8\xff\xd9")

    cases = [
        GoldenCase(
            case_id=f"C{i}",
            capture=CaptureRef(f"CAP-{i}", Decimal("12300"), CAPTURED_AT),
            truth_amount=Decimal("12300"),
            truth_date=CAPTURED_DATE,
            image_path=str(image),
            scenario="정상",
            condition="양호",
        )
        for i in range(2)
    ]
    path = tmp_path / "goldenset.json"
    goldenset.save(cases, path)
    return path


class TestLoadRepoEnv:
    def test_환경변수가_있으면_그걸_쓴다(self, monkeypatch):
        monkeypatch.setenv("GEMINI_API_KEY", "from-env")
        assert cli.load_repo_env("GEMINI_API_KEY") == "from-env"

    def test_없으면_저장소_루트_env_에서_찾는다(self, monkeypatch, tmp_path):
        monkeypatch.delenv("GEMINI_API_KEY", raising=False)
        (tmp_path / ".env").write_text(
            "# GEMINI_API_KEY=commented-out\nOTHER=x\nGEMINI_API_KEY='from-file'\n",
            encoding="utf-8",
        )
        monkeypatch.setattr(cli, "REPO_ROOT", tmp_path)

        # 주석 처리된 줄을 키로 오인하면 안 되고, 따옴표는 벗겨야 한다.
        assert cli.load_repo_env("GEMINI_API_KEY") == "from-file"

    def test_env_파일이_없으면_빈_문자열(self, monkeypatch, tmp_path):
        monkeypatch.delenv("GEMINI_API_KEY", raising=False)
        monkeypatch.setattr(cli, "REPO_ROOT", tmp_path / "nowhere")
        assert cli.load_repo_env("GEMINI_API_KEY") == ""

    def test_env_에_키가_없으면_빈_문자열(self, monkeypatch, tmp_path):
        monkeypatch.delenv("GEMINI_API_KEY", raising=False)
        (tmp_path / ".env").write_text("OTHER=x\n", encoding="utf-8")
        monkeypatch.setattr(cli, "REPO_ROOT", tmp_path)
        assert cli.load_repo_env("GEMINI_API_KEY") == ""


class TestBuildProvider:
    @pytest.mark.parametrize(
        "kind,expected_suffix",
        [("local", ""), ("local-prep", "+prep"), ("local-multipass", "+multipass")],
    )
    def test_local_계열은_전처리_다중패스_플래그가_이름에_드러난다(self, kind, expected_suffix):
        args = cli.build_parser().parse_args(["run", "--provider", kind])
        provider = cli._build_provider(args)
        assert provider.name.endswith(expected_suffix)

    def test_gemini_는_키가_없으면_기동을_거부한다(self, monkeypatch, tmp_path):
        # 키 없이 조용히 돌면 전 건이 실패로 집계돼 "모델이 나쁘다" 로 오독된다.
        monkeypatch.delenv("GEMINI_API_KEY", raising=False)
        monkeypatch.setattr(cli, "REPO_ROOT", tmp_path)
        args = cli.build_parser().parse_args(["run", "--provider", "gemini"])

        with pytest.raises(SystemExit, match="GEMINI_API_KEY"):
            cli._build_provider(args)

    def test_gemini_는_키가_있으면_가격까지_옮겨_담는다(self, monkeypatch):
        monkeypatch.setenv("GEMINI_API_KEY", "k")
        args = cli.build_parser().parse_args(
            ["run", "--provider", "gemini", "--price-in", "0.30", "--price-out", "2.50"]
        )
        provider = cli._build_provider(args)

        assert "gemini-2.5-flash" in provider.name

    def test_알_수_없는_프로바이더는_거부한다(self):
        args = cli.build_parser().parse_args(["run"])
        args.provider = "made-up"

        with pytest.raises(SystemExit, match="알 수 없는 프로바이더"):
            cli._build_provider(args)


class TestCmdBuild:
    def test_골든셋과_이미지를_만들고_분포를_보고한다(self, tmp_path, monkeypatch, capsys):
        # 이미지 경로는 SERVICE_ROOT 기준 상대경로로 굳어야 한다(절대경로는 남의 머신에서 깨진다).
        monkeypatch.setattr(cli, "SERVICE_ROOT", tmp_path)
        images = tmp_path / "build" / "images"
        out = tmp_path / "data" / "goldenset.json"

        args = cli.build_parser().parse_args(
            ["build", "--count", "3", "--seed", "7", "--out", str(out), "--images", str(images)]
        )
        assert cli.cmd_build(args) == 0

        payload = json.loads(out.read_text(encoding="utf-8"))
        assert payload["count"] == 3
        for case in payload["cases"]:
            assert not pathlib.Path(case["image_path"]).is_absolute()
            assert (tmp_path / case["image_path"]).exists()

        printed = capsys.readouterr().out
        assert "골든셋 3건 생성" in printed
        assert "정답 판정 분포" in printed

    def test_같은_시드는_같은_셋을_만든다(self, tmp_path, monkeypatch):
        monkeypatch.setattr(cli, "SERVICE_ROOT", tmp_path)
        outs = []
        for run in range(2):
            out = tmp_path / f"data/set{run}.json"
            args = cli.build_parser().parse_args(
                ["build", "--count", "3", "--seed", "7", "--out", str(out),
                 "--images", str(tmp_path / "build" / f"images{run}")]
            )
            cli.cmd_build(args)
            payload = json.loads(out.read_text(encoding="utf-8"))
            outs.append([(c["case_id"], c["truth_amount"], c["truth_date"]) for c in payload["cases"]])

        assert outs[0] == outs[1], "시드가 같은데 셋이 다르면 비교가 성립하지 않는다"


class TestCmdRun:
    def test_리포트를_찍고_0_을_반환한다(self, goldenset_file, monkeypatch, capsys):
        provider = StubProvider()
        monkeypatch.setattr(cli, "_build_provider", lambda args: provider)

        args = cli.build_parser().parse_args(["run", "--goldenset", str(goldenset_file)])
        assert cli.cmd_run(args) == 0

        out = capsys.readouterr().out
        assert "stub:v1 으로 2건 평가 중" in out
        assert "대사 판정 일치율" in out
        assert "시나리오별 단면" in out
        assert provider.calls == 2

    def test_limit_은_앞에서_N건만_돌린다(self, goldenset_file, monkeypatch):
        provider = StubProvider()
        monkeypatch.setattr(cli, "_build_provider", lambda args: provider)

        args = cli.build_parser().parse_args(
            ["run", "--goldenset", str(goldenset_file), "--limit", "1"]
        )
        cli.cmd_run(args)

        assert provider.calls == 1

    def test_workers_가_1이_아니면_지연_수치를_믿지_말라고_경고한다(self, goldenset_file,
                                                                monkeypatch, capsys):
        monkeypatch.setattr(cli, "_build_provider", lambda args: StubProvider())

        args = cli.build_parser().parse_args(
            ["run", "--goldenset", str(goldenset_file), "--workers", "4"]
        )
        cli.cmd_run(args)

        assert "지연 수치는 모델 응답시간이 아닙니다" in capsys.readouterr().out

    def test_save_는_txt_와_json_을_남긴다(self, goldenset_file, monkeypatch, tmp_path, capsys):
        reports = tmp_path / "reports"
        monkeypatch.setattr(cli, "DEFAULT_REPORTS", reports)
        monkeypatch.setattr(cli, "_build_provider", lambda args: StubProvider())

        args = cli.build_parser().parse_args(
            ["run", "--goldenset", str(goldenset_file), "--save"]
        )
        cli.cmd_run(args)

        # 파일명에 ':' 가 그대로 들어가면 Windows 에서 저장이 실패한다.
        assert (reports / "stub_v1.txt").exists()
        saved = json.loads((reports / "stub_v1.json").read_text(encoding="utf-8"))
        assert saved["provider"] == "stub:v1"
        assert saved["n"] == 2
        for key in ("accuracy", "critical_false_mismatch", "critical_false_match",
                    "review_rate", "unavailable_rate", "ece", "total_cost_usd"):
            assert key in saved, f"리포트 JSON 에 {key} 가 없다"
        assert "리포트 저장" in capsys.readouterr().out


class TestCmdServe:
    def test_uvicorn_에_앱_경로와_포트를_넘긴다(self, monkeypatch):
        recorded = {}

        class FakeUvicorn:
            @staticmethod
            def run(app, host, port, log_level):
                recorded.update(app=app, host=host, port=port, log_level=log_level)

        monkeypatch.setitem(__import__("sys").modules, "uvicorn", FakeUvicorn)

        args = cli.build_parser().parse_args(["serve", "--port", "9123", "--host", "127.0.0.1"])
        assert cli.cmd_serve(args) == 0
        assert recorded == {
            "app": "receipt_ocr.api.app:app",
            "host": "127.0.0.1",
            "port": 9123,
            "log_level": "info",
        }


class TestParser:
    def test_서브커맨드는_필수다(self):
        with pytest.raises(SystemExit):
            cli.build_parser().parse_args([])

    def test_교정_파이프라인도_같은_진입점에_등록된다(self):
        # 절차를 명령어 구조로 못박은 것이므로, 등록이 빠지면 사람이 다른 순서를 밟게 된다.
        args = cli.build_parser().parse_args(["calibrate", "fit", "--dataset", "data/trainset.json"])
        assert args.calib_command == "fit"

    def test_main_은_고른_서브커맨드로_위임하고_종료코드를_돌려준다(self, monkeypatch):
        called = {}

        def fake_build(args):
            called["count"] = args.count
            return 0

        # build_parser 안에서 전역 이름으로 조회되므로, main 호출 전에 갈아끼우면 반영된다.
        monkeypatch.setattr(cli, "cmd_build", fake_build)

        assert cli.main(["build", "--count", "2", "--out", "x.json", "--images", "y"]) == 0
        assert called == {"count": 2}
