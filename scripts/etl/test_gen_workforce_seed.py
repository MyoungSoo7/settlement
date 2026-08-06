import csv
import hashlib
import io
import importlib.util
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("gen-workforce-seed.py")
REPOSITORY_ROOT = MODULE_PATH.parents[2]
OLD_MIGRATION = REPOSITORY_ROOT / "company-service/src/main/resources/db/migration/V20260804090000__seed_workforce_2026_06.sql"
SPEC = importlib.util.spec_from_file_location("gen_workforce_seed", MODULE_PATH)
generator = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(generator)


HEADER = [
    "자료생성년월", "사업장명", "사업자등록번호", "사업장가입상태코드 1 등록 2 탈퇴",
    "가입자수", "사업장지번상세주소", "사업장도로명상세주소", "우편번호", "사업장전화번호",
    "사업장팩스번호", "사업장전자우편주소", "사업장홈페이지", "사업장등록일", "사업장업종코드",
    "사업장업종코드명", "사업장형태구분코드", "사업장형태구분코드명", "가입자수증감", "가입자수",
    "당월고지금액", "신규취득자수", "상실가입자수",
]


def row(**overrides):
    values = {
        "자료생성년월": "2026-06",
        "사업장명": "서울 소프트웨어",
        "사업자등록번호": "123456",
        "사업장가입상태코드 1 등록 2 탈퇴": "1",
        "사업장지번상세주소": "서울특별시 성동구 연무장길 1",
        "사업장도로명상세주소": "서울특별시 성동구 왕십리로 1",
        "사업장업종코드": "722000",
        "사업장업종코드명": "응용 소프트웨어 개발 및 공급업",
        "가입자수": "10",
        "당월고지금액": "950000",
    }
    values.update(overrides)
    return [values.get(column, "") for column in HEADER]


class WorkforceSeedGeneratorTest(unittest.TestCase):
    def write_csv(self, directory, *rows):
        path = Path(directory) / "fixture.csv"
        with path.open("w", encoding="cp949", newline="") as handle:
            writer = csv.writer(handle)
            writer.writerow(HEADER)
            writer.writerows(rows)
        return path

    def load(self, path):
        return generator.load_source(path, "2026-06")

    def test_accepts_seoul_road_address_software_row(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.load(self.write_csv(directory, row()))

        self.assertEqual(1, result.candidate_count)
        self.assertEqual(1, result.accepted_count)
        self.assertEqual("서울특별시 성동구 왕십리로 1", result.rows[0].address)
        self.assertEqual("서울특별시", result.rows[0].sido)

    def test_accepts_lot_address_when_road_address_is_blank(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.load(self.write_csv(directory, row(
                **{"사업장도로명상세주소": "", "사업장지번상세주소": "서울특별시 강남구 테헤란로 2",
                   "사업장업종코드": "724000"}
            )))

        self.assertEqual(1, result.accepted_count)
        self.assertEqual("서울특별시 강남구 테헤란로 2", result.rows[0].address)

    def test_rejects_gyeonggi_row_outside_seoul_scope(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.load(self.write_csv(directory, row(
                **{"사업장도로명상세주소": "경기도 성남시 분당구 판교로 1",
                   "사업장지번상세주소": "경기도 성남시 분당구 판교동 1"}
            )))

        self.assertEqual(0, result.candidate_count)
        self.assertEqual(0, result.accepted_count)
        self.assertEqual(0, result.rejected_count)

    def test_rejects_computer_retail_even_when_in_seoul(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.load(self.write_csv(directory, row(**{"사업장업종코드": "523532"})))

        self.assertEqual(0, result.candidate_count)
        self.assertEqual(0, result.accepted_count)

    def test_rejects_withdrawn_status(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.load(self.write_csv(directory, row(
                **{"사업장가입상태코드 1 등록 2 탈퇴": "2"}
            )))

        self.assertEqual(0, result.candidate_count)
        self.assertEqual(0, result.accepted_count)

    def test_rejects_unknown_or_blank_status_instead_of_treating_it_as_active(self):
        with tempfile.TemporaryDirectory() as directory:
            for status in ("", "9"):
                with self.subTest(status=status):
                    result = self.load(self.write_csv(
                        directory, row(**{"사업장가입상태코드 1 등록 2 탈퇴": status})
                    ))
                    self.assertEqual(0, result.candidate_count)
                    self.assertEqual(0, result.accepted_count)

    def test_rejects_non_positive_billed_amount_and_counts_candidate_rejection(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.load(self.write_csv(directory, row(**{"당월고지금액": "0"})))

        self.assertEqual(1, result.candidate_count)
        self.assertEqual(0, result.accepted_count)
        self.assertEqual(1, result.rejected_count)

    def test_duplicate_name_and_prefix_uses_last_row_and_counts_rejection(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.load(self.write_csv(
                directory,
                row(**{"당월고지금액": "950000"}),
                row(**{"당월고지금액": "1900000"}),
            ))

        self.assertEqual(2, result.candidate_count)
        self.assertEqual(1, result.accepted_count)
        self.assertEqual(1, result.rejected_count)
        self.assertEqual(1900000, result.rows[0].monthly_billed_amount)

    def test_aborts_when_any_source_row_uses_a_different_snapshot_month(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_csv(directory, row(), row(**{"자료생성년월": "2026-05"}))
            with self.assertRaisesRegex(ValueError, "2026-05"):
                self.load(path)

    def test_refuses_to_overwrite_existing_output(self):
        with tempfile.TemporaryDirectory() as directory:
            csv_path = self.write_csv(directory, row())
            output = Path(directory) / "existing.sql"
            output.write_text("keep me", encoding="utf-8")

            with self.assertRaises(FileExistsError):
                generator.generate(csv_path, output, "2026-07-23", "2026-06")

            self.assertEqual("keep me", output.read_text(encoding="utf-8"))

    def test_rendered_sql_has_fixture_provenance_scope_rate_and_exact_numeric_median(self):
        with tempfile.TemporaryDirectory() as directory:
            csv_path = self.write_csv(directory, row(), row(**{"사업장명": "서울 데이터", "사업자등록번호": "654321",
                                                               "사업장업종코드": "724000"}))
            source = self.load(csv_path)
            rendered = generator.render_sql(source, "2026-07-23", "2026-06")
            expected_hash = hashlib.sha256(csv_path.read_bytes()).hexdigest().upper()
        self.assertIn("snapshot_month = '2026-06'", rendered)
        self.assertIn("'2026-06'", rendered)
        self.assertIn("0.095", rendered)
        self.assertIn("SEOUL_IT_FULL", rendered)
        self.assertIn("SOFTWARE_IT_SERVICE", rendered)
        self.assertIn(expected_hash, rendered)
        self.assertIn("raw_source_row_count = 2", rendered)
        self.assertIn("accepted_row_count = 2", rendered)
        self.assertIn("ROW_NUMBER() OVER", rendered)
        self.assertIn("WHERE row_number IN ((group_count + 1) / 2, (group_count + 2) / 2)", rendered)
        self.assertIn("LOCK TABLE company_workforce,", rendered)
        self.assertIn("IN SHARE ROW EXCLUSIVE MODE;", rendered)
        self.assertLess(rendered.index("LOCK TABLE company_workforce,"), rendered.index("DO $$"))
        self.assertNotIn("ALTER TABLE workforce_aggregate_build", rendered)
        self.assertNotRegex(rendered, r"(?m)^BEGIN;$")
        self.assertNotRegex(rendered, r"(?m)^COMMIT;$")
        self.assertNotIn("double precision", rendered.lower())
        self.assertNotIn("C:\\Users", rendered)
        self.assertNotIn(str(csv_path), rendered)

    def test_fingerprints_every_known_old_seed_column_deterministically(self):
        fingerprint = generator.fingerprint_seed_migration(OLD_MIGRATION, "2026-06")

        self.assertEqual(4247, fingerprint.row_count)
        self.assertEqual("246de1b02d14f86ccf751c96d3956059", fingerprint.md5)

        with tempfile.TemporaryDirectory() as directory:
            source = self.load(self.write_csv(directory, row()))
            rendered = generator.render_sql(source, "2026-07-23", "2026-06")

        self.assertIn("actual_fingerprint", rendered)
        self.assertIn("246de1b02d14f86ccf751c96d3956059", rendered)
        self.assertNotIn("COUNT(*) FROM company_workforce WHERE snapshot_month = '2026-06') <> 4247", rendered)

    def test_refuses_malformed_or_injectable_snapshot_month_before_rendering(self):
        with tempfile.TemporaryDirectory() as directory:
            csv_path = self.write_csv(directory, row())
            source = self.load(csv_path)

            for invalid_month in ("0000-06", "2026-6", "2026-13", "2026-06'; DROP TABLE company_workforce; --"):
                with self.subTest(invalid_month=invalid_month):
                    with self.assertRaises(ValueError):
                        generator.render_sql(source, "2026-07-23", invalid_month)

    def test_refuses_malformed_or_injectable_release_date_before_rendering(self):
        with tempfile.TemporaryDirectory() as directory:
            source = self.load(self.write_csv(directory, row()))

            for invalid_date in ("2026-7-23", "2026-02-30", "2026-07-23'; COMMIT; --"):
                with self.subTest(invalid_date=invalid_date):
                    with self.assertRaises(ValueError):
                        generator.render_sql(source, invalid_date, "2026-06")

    def test_parses_and_hashes_a_single_immutable_source_payload(self):
        payload = io.BytesIO()
        text = io.TextIOWrapper(payload, encoding="cp949", newline="")
        writer = csv.writer(text)
        writer.writerow(HEADER)
        writer.writerow(row())
        text.flush()
        source_bytes = payload.getvalue()
        text.detach()

        class PayloadOnlyPath:
            def read_bytes(self):
                return source_bytes

            def open(self, *args, **kwargs):
                raise AssertionError("source must be parsed from the immutable byte payload")

        result = generator.load_source(PayloadOnlyPath(), "2026-06")

        self.assertEqual(hashlib.sha256(source_bytes).hexdigest().upper(), result.source_sha256)
        self.assertEqual(1, result.accepted_count)


if __name__ == "__main__":
    unittest.main()
