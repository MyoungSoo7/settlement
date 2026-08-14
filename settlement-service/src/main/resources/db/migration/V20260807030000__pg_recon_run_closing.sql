-- PG 대사 마감(closing) — 확정된 (PG, 날짜) 기간을 잠가 사후 조정을 막는다.
--
-- 배경: 같은 파일 재업로드는 file_sha256 으로 이미 멱등 차단된다. 그러나 다른 파일이 같은
-- (pg_provider, target_date) 로 들어오면 새 run 이 열려, 이미 정산·지급이 끝난 기간에 새 불일치와
-- 새 clawback 이 생길 수 있다. 마감은 그 경로를 닫는다.
--
-- status 는 varchar(20) 이라 'CLOSED'(6자)가 그대로 들어간다 — 컬럼 변경 불필요.

ALTER TABLE public.pg_reconciliation_runs
    ADD COLUMN IF NOT EXISTS closed_by varchar(100),
    ADD COLUMN IF NOT EXISTS closed_at timestamp(6);

COMMENT ON COLUMN public.pg_reconciliation_runs.closed_by IS '마감 수행 운영자 — 감사 추적';
COMMENT ON COLUMN public.pg_reconciliation_runs.closed_at IS '마감 시각 — CLOSED 전이 시점';

-- 마감 조회 인덱스: 새 대사 업로드마다 (provider, date, status=CLOSED) 를 확인하므로
-- 업로드 경로의 상시 조회다. CLOSED 행만 담는 부분 인덱스로 크기를 최소화한다.
CREATE INDEX IF NOT EXISTS idx_pg_recon_runs_closed_period
    ON public.pg_reconciliation_runs (pg_provider, target_date)
    WHERE status = 'CLOSED';
