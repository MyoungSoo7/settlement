#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate a deterministic Seoul software/IT workforce Flyway migration."""

import argparse
import csv
import hashlib
import io
import re
from dataclasses import dataclass
from datetime import date
from pathlib import Path


ALLOWED_INDUSTRY_CODES = frozenset({
    "642004", "721000", "722000", "722001", "722002", "722003",
    "722004", "722005", "723001", "724000", "729000", "940926",
})
SOURCE_ENCODING = "cp949"
CHUNK_SIZE = 500
RATE_LITERAL = "0.095"
KNOWN_SEED_COUNT = 4247
SIDO_SUFFIXES = ("특별시", "광역시", "특별자치시", "특별자치도")
MONTH_PATTERN = re.compile(r"\d{4}-\d{2}")
DATE_PATTERN = re.compile(r"\d{4}-\d{2}-\d{2}")

COL_MONTH = "자료생성년월"
COL_NAME = "사업장명"
COL_PREFIX = "사업자등록번호"
COL_STATUS = "사업장가입상태코드 1 등록 2 탈퇴"
COL_LOT_ADDRESS = "사업장지번상세주소"
COL_ROAD_ADDRESS = "사업장도로명상세주소"
COL_INDUSTRY_CODE = "사업장업종코드"
COL_INDUSTRY_NAME = "사업장업종코드명"
COL_HEADCOUNT = "가입자수"
COL_AMOUNT = "당월고지금액"


@dataclass(frozen=True)
class WorkforceRow:
    name: str
    prefix: str
    industry_code: str
    industry_name: str
    address: str
    sido: str
    sigungu: str | None
    headcount: int
    monthly_billed_amount: int


@dataclass(frozen=True)
class SourceData:
    rows: tuple[WorkforceRow, ...]
    source_sha256: str
    raw_source_count: int
    candidate_count: int
    accepted_count: int
    rejected_count: int


def parse_region(address: str) -> tuple[str | None, str | None]:
    """Parse the same top-level administrative region shape as the Java domain."""
    tokens = address.strip().split() if address else []
    if not tokens:
        return None, None
    sido = tokens[0]
    if not (sido.endswith(SIDO_SUFFIXES) or (sido.endswith("도") and len(sido) >= 3)):
        return None, None
    sigungu = None
    if len(tokens) > 1:
        candidate = tokens[1]
        if len(candidate) >= 2 and candidate.endswith(("시", "군", "구")):
            sigungu = candidate
    return sido, sigungu


def _value(record: dict[str | None, str | list[str] | None], column: str) -> str:
    value = record.get(column, "")
    return value.strip() if isinstance(value, str) else ""


def validate_snapshot_month(snapshot_month: str) -> None:
    if not MONTH_PATTERN.fullmatch(snapshot_month):
        raise ValueError(f"snapshot month must be YYYY-MM: {snapshot_month!r}")
    year, month = (int(part) for part in snapshot_month.split("-"))
    if year < 1 or not 1 <= month <= 12:
        raise ValueError(f"snapshot month is not calendar-valid: {snapshot_month!r}")


def validate_release_date(release_date: str) -> None:
    if not DATE_PATTERN.fullmatch(release_date):
        raise ValueError(f"release date must be YYYY-MM-DD: {release_date!r}")
    try:
        date.fromisoformat(release_date)
    except ValueError as exc:
        raise ValueError(f"release date is not calendar-valid: {release_date!r}") from exc


def load_source(csv_path: Path, snapshot_month: str) -> SourceData:
    """Read one strict-CP949 source file and return the scoped unique cohort."""
    validate_snapshot_month(snapshot_month)
    source_bytes = csv_path.read_bytes()
    source_sha256 = hashlib.sha256(source_bytes).hexdigest().upper()
    rows_by_key: dict[tuple[str, str], WorkforceRow] = {}
    raw_source_count = candidate_count = rejected_count = 0

    with io.TextIOWrapper(io.BytesIO(source_bytes), encoding=SOURCE_ENCODING, newline="") as source:
        reader = csv.DictReader(source)
        required = {
            COL_MONTH, COL_NAME, COL_PREFIX, COL_STATUS, COL_LOT_ADDRESS,
            COL_ROAD_ADDRESS, COL_INDUSTRY_CODE, COL_INDUSTRY_NAME,
            COL_HEADCOUNT, COL_AMOUNT,
        }
        missing = required.difference(reader.fieldnames or ())
        if missing:
            raise ValueError(f"CSV is missing required columns: {', '.join(sorted(missing))}")

        for line_number, record in enumerate(reader, start=2):
            raw_source_count += 1
            row_month = _value(record, COL_MONTH)
            if row_month != snapshot_month:
                raise ValueError(
                    f"snapshot month mismatch at CSV line {line_number}: "
                    f"expected {snapshot_month}, got {row_month or '<blank>'}"
                )
            if _value(record, COL_STATUS) != "1":
                continue
            industry_code = _value(record, COL_INDUSTRY_CODE)
            if industry_code not in ALLOWED_INDUSTRY_CODES:
                continue
            address = _value(record, COL_ROAD_ADDRESS) or _value(record, COL_LOT_ADDRESS)
            sido, sigungu = parse_region(address)
            if sido != "서울특별시":
                continue

            candidate_count += 1
            name = _value(record, COL_NAME)
            prefix = _value(record, COL_PREFIX)
            try:
                headcount = int(_value(record, COL_HEADCOUNT))
                amount = int(_value(record, COL_AMOUNT))
            except ValueError:
                rejected_count += 1
                continue
            if not name or not re.fullmatch(r"\d{6}", prefix) or headcount <= 0 or amount <= 0:
                rejected_count += 1
                continue

            key = (name, prefix)
            if key in rows_by_key:
                rejected_count += 1
            rows_by_key[key] = WorkforceRow(
                name=name,
                prefix=prefix,
                industry_code=industry_code,
                industry_name=_value(record, COL_INDUSTRY_NAME),
                address=address,
                sido=sido,
                sigungu=sigungu,
                headcount=headcount,
                monthly_billed_amount=amount,
            )

    ordered_rows = tuple(rows_by_key[key] for key in sorted(rows_by_key))
    return SourceData(
        rows=ordered_rows,
        source_sha256=source_sha256,
        raw_source_count=raw_source_count,
        candidate_count=candidate_count,
        accepted_count=len(ordered_rows),
        rejected_count=rejected_count,
    )


def sql_literal(value: str | None) -> str:
    if value is None:
        return "NULL"
    return "'" + value.replace("'", "''") + "'"


def _row_values(row: WorkforceRow, snapshot_month: str) -> str:
    return (
        f"({sql_literal(row.name)}, {sql_literal(row.prefix)}, {sql_literal(row.industry_code)}, "
        f"{sql_literal(row.industry_name)}, {sql_literal(row.address)}, {sql_literal(row.sido)}, "
        f"{sql_literal(row.sigungu)}, {sql_literal(snapshot_month)}, {row.headcount}, "
        f"{row.monthly_billed_amount})"
    )


def _insert_chunks(rows: tuple[WorkforceRow, ...], snapshot_month: str) -> list[str]:
    statements = []
    for start in range(0, len(rows), CHUNK_SIZE):
        values = ",\n".join(_row_values(row, snapshot_month) for row in rows[start:start + CHUNK_SIZE])
        statements.append(f"""INSERT INTO company_workforce
    (workplace_name, biz_reg_no_prefix, industry_code, industry_name, address,
     sido, sigungu, snapshot_month, headcount, monthly_billed_amount)
VALUES
{values};""")
    return statements


def _aggregate_sql(snapshot_month: str) -> str:
    return f"""WITH eligible AS (
    SELECT workplace_name,
           COALESCE(biz_reg_no_prefix, '') AS biz_reg_no_prefix,
           NULLIF(TRIM(industry_code), '') AS industry_code,
           sido,
           sigungu,
           headcount::numeric AS headcount,
           ROUND(monthly_billed_amount * 12 / (headcount * {RATE_LITERAL}::numeric), 0) AS est_salary
    FROM company_workforce
    WHERE snapshot_month = '{snapshot_month}' AND headcount > 0 AND monthly_billed_amount > 0
),
grouped AS (
    SELECT 'INDUSTRY' AS axis, 'EXACT' AS level, industry_code AS group_key,
           workplace_name, biz_reg_no_prefix, headcount, est_salary
    FROM eligible WHERE industry_code IS NOT NULL
    UNION ALL
    SELECT 'INDUSTRY', 'BROADENED', LEFT(industry_code, 3),
           workplace_name, biz_reg_no_prefix, headcount, est_salary
    FROM eligible WHERE industry_code IS NOT NULL
    UNION ALL
    SELECT 'REGION', 'EXACT', sido || ' ' || sigungu,
           workplace_name, biz_reg_no_prefix, headcount, est_salary
    FROM eligible WHERE sido IS NOT NULL AND sigungu IS NOT NULL
    UNION ALL
    SELECT 'REGION', 'BROADENED', sido,
           workplace_name, biz_reg_no_prefix, headcount, est_salary
    FROM eligible WHERE sido IS NOT NULL
),
metric_values(axis, level, group_key, metric, metric_value) AS (
    SELECT axis, level, group_key, 'HEADCOUNT', headcount FROM grouped
    UNION ALL
    SELECT axis, level, group_key, 'ESTIMATED_ANNUAL_SALARY', est_salary FROM grouped
),
ranked AS (
    SELECT axis, level, group_key, metric, metric_value,
           ROW_NUMBER() OVER (PARTITION BY axis, level, group_key, metric ORDER BY metric_value) AS row_number,
           COUNT(*) OVER (PARTITION BY axis, level, group_key, metric) AS group_count
    FROM metric_values
)
INSERT INTO workforce_aggregate
    (snapshot_month, axis, level, group_key, metric, median, sample_size)
SELECT '{snapshot_month}', axis, level, group_key, metric,
       ROUND(AVG(metric_value), 2), MAX(group_count)
FROM ranked
WHERE row_number IN ((group_count + 1) / 2, (group_count + 2) / 2)
GROUP BY axis, level, group_key, metric;

WITH eligible AS (
    SELECT workplace_name,
           COALESCE(biz_reg_no_prefix, '') AS biz_reg_no_prefix,
           NULLIF(TRIM(industry_code), '') AS industry_code,
           sido,
           sigungu,
           headcount::numeric AS headcount,
           ROUND(monthly_billed_amount * 12 / (headcount * {RATE_LITERAL}::numeric), 0) AS est_salary
    FROM company_workforce
    WHERE snapshot_month = '{snapshot_month}' AND headcount > 0 AND monthly_billed_amount > 0
),
grouped AS (
    SELECT 'INDUSTRY' AS axis, 'EXACT' AS level, industry_code AS group_key,
           workplace_name, biz_reg_no_prefix, headcount, est_salary
    FROM eligible WHERE industry_code IS NOT NULL
    UNION ALL SELECT 'INDUSTRY', 'BROADENED', LEFT(industry_code, 3), workplace_name, biz_reg_no_prefix, headcount, est_salary FROM eligible WHERE industry_code IS NOT NULL
    UNION ALL SELECT 'REGION', 'EXACT', sido || ' ' || sigungu, workplace_name, biz_reg_no_prefix, headcount, est_salary FROM eligible WHERE sido IS NOT NULL AND sigungu IS NOT NULL
    UNION ALL SELECT 'REGION', 'BROADENED', sido, workplace_name, biz_reg_no_prefix, headcount, est_salary FROM eligible WHERE sido IS NOT NULL
)
INSERT INTO workforce_percentile
    (snapshot_month, workplace_name, biz_reg_no_prefix, axis, level, metric, percentile)
SELECT snapshot_month, workplace_name, biz_reg_no_prefix, axis, level, metric, percentile
FROM (
    SELECT '{snapshot_month}' AS snapshot_month, workplace_name, biz_reg_no_prefix, axis, level,
           'HEADCOUNT' AS metric,
           ROUND((CUME_DIST() OVER (PARTITION BY axis, level, group_key ORDER BY headcount))::numeric * 100, 2) AS percentile
    FROM grouped
    UNION ALL
    SELECT '{snapshot_month}', workplace_name, biz_reg_no_prefix, axis, level,
           'ESTIMATED_ANNUAL_SALARY',
           ROUND((CUME_DIST() OVER (PARTITION BY axis, level, group_key ORDER BY est_salary))::numeric * 100, 2)
    FROM grouped
) ranked
WHERE biz_reg_no_prefix <> '';"""


def render_sql(source: SourceData, release_date: str, snapshot_month: str) -> str:
    """Render a byte-stable forward-only Flyway migration without source paths."""
    validate_release_date(release_date)
    validate_snapshot_month(snapshot_month)
    metadata = f"""-- Description: Replace the known workforce sample with the complete Seoul software/IT-service cohort
-- Author: Codex
-- Date: {release_date}
-- Rollback: Forward-only; create a new corrective migration if needed.
-- Source SHA-256: {source.source_sha256}
-- raw_source_row_count = {source.raw_source_count}; source_row_count = {source.candidate_count}; accepted_row_count = {source.accepted_count}; rejected_row_count = {source.rejected_count}
-- Coverage: SEOUL_IT_FULL; region: SEOUL; industry scope: SOFTWARE_IT_SERVICE
-- Flyway owns this migration's transaction boundary.
-- Block concurrent inserts, deletes, updates, and rebuilds while preserving existing read queries.
LOCK TABLE company_workforce,
           workforce_aggregate_build,
           workforce_aggregate,
           workforce_percentile
IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM company_workforce WHERE snapshot_month = '{snapshot_month}')
       AND (SELECT COUNT(*) FROM company_workforce WHERE snapshot_month = '{snapshot_month}') <> {KNOWN_SEED_COUNT} THEN
        RAISE EXCEPTION 'Refusing to replace unknown workforce dataset for {snapshot_month}';
    END IF;
END $$;

DELETE FROM workforce_percentile WHERE snapshot_month = '{snapshot_month}';
DELETE FROM workforce_aggregate WHERE snapshot_month = '{snapshot_month}';
DELETE FROM workforce_aggregate_build WHERE snapshot_month = '{snapshot_month}';
DELETE FROM company_workforce WHERE snapshot_month = '{snapshot_month}';
"""
    build_start = f"""INSERT INTO workforce_aggregate_build
    (snapshot_month, status, source_row_count, accepted_row_count, rejected_row_count, built_at,
     source_release_date, source_sha256, raw_source_row_count, coverage_scope, region_scope, industry_scope)
VALUES
    ('{snapshot_month}', 'BUILDING', {source.candidate_count}, {source.accepted_count}, {source.rejected_count}, NOW(),
     DATE '{release_date}', '{source.source_sha256}', {source.raw_source_count}, 'SEOUL_IT_FULL', 'SEOUL', 'SOFTWARE_IT_SERVICE');
"""
    complete = f"""UPDATE workforce_aggregate_build
SET status = 'COMPLETE', built_at = NOW()
WHERE snapshot_month = '{snapshot_month}';
"""
    return "\n".join([metadata, build_start, *_insert_chunks(source.rows, snapshot_month),
                      _aggregate_sql(snapshot_month), complete])


def generate(csv_path: Path, output_path: Path, release_date: str, snapshot_month: str) -> SourceData:
    """Generate via exclusive create, preserving any existing migration intact."""
    source = load_source(csv_path, snapshot_month)
    rendered = render_sql(source, release_date, snapshot_month)
    with output_path.open("x", encoding="utf-8", newline="\n") as output:
        output.write(rendered)
    return source


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", required=True, type=Path, help="strict CP949 NPS source CSV")
    parser.add_argument("--output", required=True, type=Path, help="new Flyway SQL path (must not already exist)")
    parser.add_argument("--release-date", required=True, help="source release date, YYYY-MM-DD")
    parser.add_argument("--snapshot-month", required=True, help="required source month, YYYY-MM")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    source = generate(args.csv, args.output, args.release_date, args.snapshot_month)
    print(
        f"wrote {args.output}: raw={source.raw_source_count}, candidates={source.candidate_count}, "
        f"accepted={source.accepted_count}, rejected={source.rejected_count}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
