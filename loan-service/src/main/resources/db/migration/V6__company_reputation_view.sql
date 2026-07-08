-- V6: 기업 평판 프로젝션 (ADR 0023 Phase 3)
--
-- loan-service 는 company_db 를 직접 읽을 수 없으므로(DB-per-service), 셀러(법인)의 평판 리스크를
-- company 의 CompanyReputationChanged 이벤트로 받아 자체 DB 에 materialize 한다. 종목코드가 PK 라
-- 이벤트 재수신 시 멱등 UPSERT. (schema prefix 는 default_schema=opslab 로 해석 — loan 자체 DB.)

CREATE TABLE IF NOT EXISTS company_reputation (
    stock_code     VARCHAR(6)  PRIMARY KEY,            -- company 측 종목코드 (이벤트로 수신)
    score          INT         NOT NULL,
    grade          VARCHAR(1)  NOT NULL,               -- A~E
    previous_grade VARCHAR(1),                         -- 직전 등급 (최초 스냅샷이면 NULL)
    snapshot_date  DATE        NOT NULL,
    updated_at     TIMESTAMP   NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_company_reputation_score CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT chk_company_reputation_grade CHECK (grade IN ('A', 'B', 'C', 'D', 'E'))
);
