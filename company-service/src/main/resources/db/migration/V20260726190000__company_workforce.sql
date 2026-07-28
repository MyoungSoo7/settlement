-- V20260726190000: 국민연금 사업장가입자 공개데이터 기반 인원수/추정연봉 조회 (조회 전용 —
-- 랭킹/스코어링/시계열 추이는 범위 밖). companies(stockCode 상장사 체계)와 무관한 독립 테이블 —
-- 원본 CSV 의 사업자등록번호는 앞 6자리만 공개되어 전국 단위로 고유하지 않으므로 사업장명
-- 텍스트 검색으로만 조회한다.

CREATE TABLE IF NOT EXISTS company_workforce (
    id                    BIGSERIAL     PRIMARY KEY,
    workplace_name        VARCHAR(200)  NOT NULL,
    biz_reg_no_prefix     VARCHAR(6),
    industry_name         VARCHAR(100),
    address               VARCHAR(300),
    snapshot_month        VARCHAR(7)    NOT NULL,   -- 자료생성년월, 'YYYY-MM'
    headcount             INT           NOT NULL,
    monthly_billed_amount NUMERIC(15,0) NOT NULL,   -- 사업장 전체 당월고지금액(국민연금 보험료 합계, 원 단위)
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    -- 월별 재적재 멱등: 같은 사업장·같은 달 재수집은 갱신(ON CONFLICT DO UPDATE)
    CONSTRAINT uq_company_workforce_snapshot UNIQUE (workplace_name, biz_reg_no_prefix, snapshot_month)
);

CREATE INDEX IF NOT EXISTS idx_company_workforce_name ON company_workforce (workplace_name);
