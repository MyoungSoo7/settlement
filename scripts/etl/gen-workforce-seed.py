# -*- coding: utf-8 -*-
"""국민연금 사업장 CSV(2026-06)에서 사업장비교 시드 마이그레이션 SQL 생성.

규칙은 전부 Java 정본을 미러링한다:
- 행 필터: CompanyWorkforceImportService.toDomainOrNull (탈퇴 제외, 사업장명 필수, 수치 파싱)
- 주소: 도로명 우선, 없으면 지번
- sido/sigungu: WorkplaceRegion.parse (접미사 규칙)
- 시드 한정 추가 제약: biz_reg_no_prefix 6자리 숫자 필수(UNIQUE NULL 중복·백분위 미저장 회피)
"""
import csv, re
from pathlib import Path

CSV_PATH = Path(r"C:\Users\iamip\IdeaProjects\kubenetis\settlement\company-service\src\main\resources\국민연금공단_국민연금 가입 사업장 내역_20260723.csv")
OUT_PATH = Path(r"C:\Users\iamip\IdeaProjects\kubenetis\settlement\company-service\src\main\resources\db\migration\V20260804090000__seed_workforce_2026_06.sql")
MONTH = "2026-06"
TOP_ANCHORS = 60          # 전국 가입자수 상위 — 업종·지역 다양성 확보
GROUP_SAMPLE = 24         # 집단(업종 EXACT·지역 EXACT)당 균등 간격 표본 수 (>= MIN_SAMPLE_SIZE 10)

FAMOUS_SUBSTR = ["삼성전자", "현대자동차", "에스케이하이닉스", "엘지전자", "기아", "쿠팡",
                 "네이버", "카카오", "셀트리온", "포스코", "한화", "두산", "롯데", "신세계",
                 "하이브", "크래프톤", "엔씨소프트", "우아한형제들", "토스", "비바리퍼블리카",
                 "무신사", "당근마켓", "마켓컬리", "컬리"]

SIDO_SUFFIXES = ("특별시", "광역시", "특별자치시", "특별자치도")

def parse_region(address):
    if not address or not address.strip():
        return None, None
    tokens = address.strip().split()
    t0 = tokens[0]
    is_sido = t0.endswith(SIDO_SUFFIXES) or (t0.endswith("도") and len(t0) >= 3)
    if not is_sido:
        return None, None
    sigungu = None
    if len(tokens) >= 2:
        t1 = tokens[1]
        if len(t1) >= 2 and (t1.endswith("시") or t1.endswith("군") or t1.endswith("구")):
            sigungu = t1
    return t0, sigungu

rows = {}  # key (name, prefix) -> row dict; 같은 키 중복은 마지막 값이 이긴다(임포트 컨벤션)
with open(CSV_PATH, encoding="cp949", errors="replace", newline="") as f:
    r = csv.reader(f)
    header = next(r)
    for c in r:
        if len(c) < 20:
            continue
        if c[3] == "2":                      # 탈퇴
            continue
        name = c[1].strip()
        if not name:
            continue
        prefix = c[2].strip()
        if not re.fullmatch(r"\d{6}", prefix):   # 시드 한정: 공란/이형 prefix 행 제외
            continue
        try:
            headcount = int(c[18].strip())
            amount = int(c[19].strip())
        except ValueError:
            continue
        if headcount <= 0 or amount <= 0:    # eligibleForComparison 만 시드
            continue
        address = c[6].strip() if c[6].strip() else c[5].strip()
        sido, sigungu = parse_region(address)
        industry_code = c[13].strip()
        industry_name = c[14].strip()
        rows[(name, prefix)] = dict(name=name, prefix=prefix,
                                    industry_code=industry_code or None,
                                    industry_name=industry_name or None,
                                    address=address or None, sido=sido, sigungu=sigungu,
                                    headcount=headcount, amount=amount)

all_rows = list(rows.values())
print(f"eligible rows: {len(all_rows)}")

by_head = sorted(all_rows, key=lambda x: -x["headcount"])
anchors = list(by_head[:TOP_ANCHORS])
famous = [x for x in all_rows
          if any(k in x["name"] for k in FAMOUS_SUBSTR) and x["headcount"] >= 300]
anchor_keys = {(a["name"], a["prefix"]) for a in anchors}
for x in famous:
    if (x["name"], x["prefix"]) not in anchor_keys:
        anchors.append(x)
        anchor_keys.add((x["name"], x["prefix"]))
print(f"anchors: {len(anchors)}")

industry_groups = {a["industry_code"] for a in anchors if a["industry_code"]}
region_groups = {(a["sido"], a["sigungu"]) for a in anchors if a["sido"] and a["sigungu"]}
print(f"industry groups: {len(industry_groups)}, region groups: {len(region_groups)}")

def even_sample(group, n):
    """가입자수 오름차순 정렬 후 균등 간격 추출 — 중앙값·백분위 분포를 보존한다."""
    if len(group) <= n:
        return group
    srt = sorted(group, key=lambda x: (x["headcount"], x["amount"]))
    idx = [round(i * (len(srt) - 1) / (n - 1)) for i in range(n)]
    return [srt[i] for i in sorted(set(idx))]

selected = {}
def add(row):
    selected[(row["name"], row["prefix"])] = row

for a in anchors:
    add(a)
for code in industry_groups:
    grp = [x for x in all_rows if x["industry_code"] == code]
    for x in even_sample(grp, GROUP_SAMPLE):
        add(x)
for sido, sigungu in region_groups:
    grp = [x for x in all_rows if x["sido"] == sido and x["sigungu"] == sigungu]
    for x in even_sample(grp, GROUP_SAMPLE):
        add(x)

seed = sorted(selected.values(), key=lambda x: (-x["headcount"], x["name"]))
print(f"seed rows: {len(seed)}")

def q(v):
    if v is None:
        return "NULL"
    return "'" + str(v).replace("'", "''") + "'"

CHUNK = 200
parts = []
parts.append(f"""-- V20260804090000: 사업장비교(국민연금 인원·추정연봉) 기준월 {MONTH} 시드
-- 원본: 국민연금공단_국민연금 가입 사업장 내역_20260723.csv (자료생성년월 {MONTH} — 2026-07-23 배포본,
-- 국민연금 공개데이터는 한 달 지연 배포라 7월 배포 파일의 데이터 월은 {MONTH} 이다).
-- 왜 시드인가: 원본 59.3만 행 전량은 마이그레이션에 담을 수 없고, 관리자 임포트 API
-- (/admin/company/workforce/import, gateway 미라우팅)는 fresh DB 마다 수동 실행이 필요해
-- CEO 사업장비교 메뉴가 기본 기동에서 빈 화면이 된다. 유명 기업 앵커 {len(anchors)}곳 + 앵커가 속한
-- 업종 EXACT {len(industry_groups)}집단·지역 EXACT {len(region_groups)}집단별 균등 간격 표본(집단당 최대 {GROUP_SAMPLE},
-- MIN_SAMPLE_SIZE=10 충족)으로 {len(seed)}행을 시드한다. 균등 간격 추출이라 집단 중앙값·백분위 분포가
-- 상위 편향 없이 보존된다. 생성 규칙은 CompanyWorkforceImportService(행 필터)·
-- WorkplaceRegion(sido/sigungu 파생)·CompanyWorkforceBulkPersistenceAdapter(컬럼 매핑) 정본과 동일
-- (생성 스크립트: scripts/etl/gen-workforce-seed.py — 새 월 CSV 가 오면 MONTH·OUT_PATH 만 바꿔 재생성).
-- 시드 한정 추가 제약: 사업자등록번호 앞6자리 공란 행 제외 — UNIQUE 가 NULL 을 서로 다른 값으로 봐
-- 관리자 임포트와 겹칠 때 중복 행이 생기고, 백분위도 저장되지 않는 행이라 시드 가치가 없다.
--
-- upsert 는 벌크 임포트 어댑터와 동일한 DO UPDATE 다. DO NOTHING 이면 안 되는 실측 이유:
-- V20260730120000 이전에 전량 임포트가 이미 끝난 볼륨(로컬 실 DB 55.4만 행에서 확인)은 기존 행의
-- industry_code/sido/sigungu 가 전부 NULL 이라, 시드 행이 전부 충돌-스킵되면 집계가 빈 집단만 남아
-- 비교가 계속 성립하지 않는다. DO UPDATE 는 그 볼륨에서도 시드 대상 행에 비교 컬럼을 채운다.
--
-- 뒤이어 WorkforceAggregatePersistenceAdapter.rebuild 와 동일한 SQL 로 {MONTH} 사전 집계·백분위를
-- 빌드하고 COMPLETE 로 표시한다 — 비교 조회는 COMPLETE 월의 집계만 읽으므로 이 단계 없이는
-- 상세 화면의 집단 비교가 성립하지 않는다. 관리자 임포트가 나중에 전량 재적재하면 같은 월 키로
-- upsert 되고 임포트가 집계를 전량 재빌드하므로 이 시드와 충돌하지 않는다.
""")

for i in range(0, len(seed), CHUNK):
    chunk = seed[i:i + CHUNK]
    values = ",\n".join(
        f"({q(x['name'])}, {q(x['prefix'])}, {q(x['industry_code'])}, {q(x['industry_name'])}, "
        f"{q(x['address'])}, {q(x['sido'])}, {q(x['sigungu'])}, '{MONTH}', {x['headcount']}, {x['amount']})"
        for x in chunk)
    parts.append(f"""INSERT INTO company_workforce
    (workplace_name, biz_reg_no_prefix, industry_code, industry_name, address,
     sido, sigungu, snapshot_month, headcount, monthly_billed_amount)
VALUES
{values}
ON CONFLICT (workplace_name, biz_reg_no_prefix, snapshot_month)
DO UPDATE SET industry_code = EXCLUDED.industry_code,
              industry_name = EXCLUDED.industry_name,
              address = EXCLUDED.address,
              sido = EXCLUDED.sido,
              sigungu = EXCLUDED.sigungu,
              headcount = EXCLUDED.headcount,
              monthly_billed_amount = EXCLUDED.monthly_billed_amount;
""")

# ── 사전 집계 빌드 (WorkforceAggregatePersistenceAdapter.rebuild 의 SQL, 월 리터럴 바인딩) ──
eligible_groups = f"""WITH eligible AS (
    SELECT workplace_name,
           COALESCE(biz_reg_no_prefix, '')                            AS biz_reg_no_prefix,
           NULLIF(TRIM(industry_code), '')                            AS industry_code,
           sido,
           sigungu,
           headcount::numeric                                         AS headcount,
           ROUND(monthly_billed_amount * 12 / (headcount * 0.09), 0)   AS est_salary
    FROM company_workforce
    WHERE snapshot_month = '{MONTH}' AND headcount > 0 AND monthly_billed_amount > 0
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
)"""

parts.append(f"""-- 집계 빌드 상태 = BUILDING (대사 카운트는 시드 관점: 이 시점 테이블의 {MONTH} 행 전량 수용).
INSERT INTO workforce_aggregate_build
    (snapshot_month, status, source_row_count, accepted_row_count, rejected_row_count, built_at)
SELECT '{MONTH}', 'BUILDING', COUNT(*), COUNT(*), 0, NOW()
FROM company_workforce WHERE snapshot_month = '{MONTH}'
ON CONFLICT (snapshot_month)
DO UPDATE SET status = 'BUILDING',
              source_row_count = EXCLUDED.source_row_count,
              accepted_row_count = EXCLUDED.accepted_row_count,
              rejected_row_count = EXCLUDED.rejected_row_count,
              built_at = EXCLUDED.built_at;

DELETE FROM workforce_aggregate WHERE snapshot_month = '{MONTH}';
DELETE FROM workforce_percentile WHERE snapshot_month = '{MONTH}';

{eligible_groups}
INSERT INTO workforce_aggregate
    (snapshot_month, axis, level, group_key, metric, median, sample_size)
SELECT '{MONTH}', axis, level, group_key, 'HEADCOUNT',
       ROUND((percentile_cont(0.5) WITHIN GROUP (ORDER BY headcount::double precision))::numeric, 2),
       COUNT(*)
FROM grouped
GROUP BY axis, level, group_key
UNION ALL
SELECT '{MONTH}', axis, level, group_key, 'ESTIMATED_ANNUAL_SALARY',
       ROUND((percentile_cont(0.5) WITHIN GROUP (ORDER BY est_salary::double precision))::numeric, 2),
       COUNT(*)
FROM grouped
GROUP BY axis, level, group_key;

{eligible_groups}
INSERT INTO workforce_percentile
    (snapshot_month, workplace_name, biz_reg_no_prefix, axis, level, metric, percentile)
SELECT snapshot_month, workplace_name, biz_reg_no_prefix, axis, level, metric, percentile
FROM (
    SELECT '{MONTH}' AS snapshot_month, workplace_name, biz_reg_no_prefix, axis, level,
           'HEADCOUNT' AS metric,
           ROUND((CUME_DIST() OVER (PARTITION BY axis, level, group_key
                                    ORDER BY headcount))::numeric * 100, 2) AS percentile
    FROM grouped
    UNION ALL
    SELECT '{MONTH}', workplace_name, biz_reg_no_prefix, axis, level, 'ESTIMATED_ANNUAL_SALARY',
           ROUND((CUME_DIST() OVER (PARTITION BY axis, level, group_key
                                    ORDER BY est_salary))::numeric * 100, 2)
    FROM grouped
) ranked
WHERE biz_reg_no_prefix <> '';

UPDATE workforce_aggregate_build SET status = 'COMPLETE', built_at = NOW()
WHERE snapshot_month = '{MONTH}';
""")

OUT_PATH.write_text("\n".join(parts), encoding="utf-8", newline="\n")
print(f"wrote {OUT_PATH} ({OUT_PATH.stat().st_size} bytes)")

# 검증용 요약
import collections
ind = collections.Counter(x["industry_code"] for x in seed if x["industry_code"])
reg = collections.Counter((x["sido"], x["sigungu"]) for x in seed if x["sido"] and x["sigungu"])
small_ind = {k: v for k, v in ind.items() if k in industry_groups and v < 10}
small_reg = {k: v for k, v in reg.items() if k in region_groups and v < 10}
print("anchor industry groups <10 samples:", small_ind)
print("anchor region groups <10 samples:", small_reg)
