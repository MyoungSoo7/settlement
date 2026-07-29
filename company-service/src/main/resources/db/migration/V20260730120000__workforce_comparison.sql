-- V20260730120000: 국민연금 사업장 업종·지역 비교 조회 + 월별 사전 집계
-- (Seed: docs/seeds/company-service-workforce-comparison.seed.yaml)
--
-- 왜 컬럼을 추가하나: 원본 CSV 에는 업종코드(6자리)와 업종명이 둘 다 오는데 V20260726190000 은
-- 명만 저장하고 코드를 버렸다. 코드가 없으면 "표본 미달 시 상위 업종(앞3자리)으로 한 단계 넓히는"
-- 폴백이 원천 봉쇄된다. 지역(sido/sigungu)도 컬럼으로 파생 저장한다 — 파싱 규칙을 SQL 과 Java 에
-- 이중 구현하면 드리프트가 나므로 적재 시 도메인 파서(WorkplaceRegion)로 한 번만 파생하고,
-- 집계 SQL 은 그 컬럼만 GROUP BY 한다.
-- 기존 적재분은 이 컬럼들이 NULL 이므로 전량 재적재가 필요하다(Seed 제약).

ALTER TABLE company_workforce ADD COLUMN IF NOT EXISTS industry_code VARCHAR(6);
ALTER TABLE company_workforce ADD COLUMN IF NOT EXISTS sido          VARCHAR(30);
ALTER TABLE company_workforce ADD COLUMN IF NOT EXISTS sigungu       VARCHAR(40);

-- 집계 빌드는 "해당 월 전체 스캔"이라 월 인덱스가 필요하다(단건 상세는 UNIQUE 제약을 그대로 탄다).
CREATE INDEX IF NOT EXISTS idx_company_workforce_month ON company_workforce (snapshot_month);

-- 월별 집단 통계 1행 = (월, 축, 단계, 그룹키, 지표). 조회 경로가 읽는 유일한 집계 원천 —
-- 상세 조회는 중앙값·표본수를 계산하지 않고 이 테이블을 읽기만 한다.
-- median 은 metric 에 따라 금액(원) 또는 인원수를 담는다. 인원수 중앙값도 percentile_cont(0.5) 라
-- 짝수 표본에서 소수가 나오므로 정수 컬럼을 쓰지 않는다. 부동소수 컬럼은 어느 경우에도 쓰지 않는다.
CREATE TABLE IF NOT EXISTS workforce_aggregate (
    snapshot_month VARCHAR(7)    NOT NULL,
    axis           VARCHAR(10)   NOT NULL,   -- INDUSTRY | REGION
    level          VARCHAR(10)   NOT NULL,   -- EXACT | BROADENED
    group_key      VARCHAR(80)   NOT NULL,
    metric         VARCHAR(24)   NOT NULL,   -- HEADCOUNT | ESTIMATED_ANNUAL_SALARY
    median         NUMERIC(20,2) NOT NULL,
    sample_size    INT           NOT NULL,
    CONSTRAINT pk_workforce_aggregate PRIMARY KEY (snapshot_month, axis, level, group_key, metric)
);

-- 사업장별 백분위(cume_dist = "이 값 이하 비율"). 백분위는 집단이 아니라 개별 사업장 값에 종속돼
-- 집계 행으로 접을 수 없으므로 적재 시점에 사업장 단위로 미리 계산해 둔다(조회 시 순위/건수 미계산).
-- EXACT·BROADENED 두 단계 모두 계산한다 — 폴백이 어느 단계로 떨어져도 조회 시 재계산이 없어야 한다.
-- biz_reg_no_prefix 는 원본에 공란이 있어 company_workforce 에서는 NULL 을 허용하지만, 여기서는
-- PK 구성요소라 COALESCE(...,'') 로 빈 문자열로 정규화해 저장한다(조회도 같은 정규화를 쓴다).
CREATE TABLE IF NOT EXISTS workforce_percentile (
    snapshot_month    VARCHAR(7)   NOT NULL,
    workplace_name    VARCHAR(200) NOT NULL,
    biz_reg_no_prefix VARCHAR(6)   NOT NULL,
    axis              VARCHAR(10)  NOT NULL,
    level             VARCHAR(10)  NOT NULL,
    metric            VARCHAR(24)  NOT NULL,
    percentile        NUMERIC(5,2) NOT NULL,   -- 0.00 ~ 100.00
    CONSTRAINT pk_workforce_percentile
        PRIMARY KEY (snapshot_month, workplace_name, biz_reg_no_prefix, axis, level, metric)
);

-- 월 단위 집계 생성 상태 = 원자적 교체의 관측 지점 + 적재 대사(sourceRowCount = accepted + rejected).
-- 빌드는 단일 트랜잭션에서 BUILDING INSERT → 교체 → COMPLETE 갱신으로 수행한다. 중간에 죽으면
-- 트랜잭션째로 롤백되어 직전 COMPLETE 집계가 그대로 남고 stale BUILDING 도 남지 않는다.
-- 조회 경로는 status='COMPLETE' 인 월의 집계만 읽는다.
CREATE TABLE IF NOT EXISTS workforce_aggregate_build (
    snapshot_month     VARCHAR(7)  PRIMARY KEY,
    status             VARCHAR(10) NOT NULL,   -- BUILDING | COMPLETE
    source_row_count   BIGINT      NOT NULL,
    accepted_row_count BIGINT      NOT NULL,
    rejected_row_count BIGINT      NOT NULL,
    built_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
