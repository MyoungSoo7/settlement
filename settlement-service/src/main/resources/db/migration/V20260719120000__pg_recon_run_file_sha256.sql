-- V20260719120000: PG 대사 run 파일 해시 멱등 — Seed KI-1 (pgreconciliation) 후속
--
-- 무엇을: pg_reconciliation_runs 에 업로드 파일 내용 SHA-256 컬럼을 추가하고,
--   COMPLETED run 에 한해 같은 해시의 중복 run 을 부분 UNIQUE 로 차단한다.
-- 왜: 같은 PG 파일을 재업로드하면 동일 차이가 새 discrepancy id 로 재생성되고, 양쪽을 각각
--   승인하면 같은 결제에 이중 clawback 이 가능했다(uq_adjustments 는 discrepancyId 기준 1:1 이라
--   파일 중복은 못 막음). 서비스는 check-then-act 로 기존 완료 run 을 반환하고(멱등),
--   이 인덱스가 동시 업로드 레이스의 최종 방어선이다.
-- FAILED run 은 제외 — 파싱 실패 등 후 같은 파일로 재시도할 수 있어야 한다.
-- 레거시 run 은 file_sha256 NULL 로 남는다(멱등 판정 대상 아님).

ALTER TABLE public.pg_reconciliation_runs
    ADD COLUMN IF NOT EXISTS file_sha256 varchar(64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_pg_recon_runs_file_sha256_completed
    ON public.pg_reconciliation_runs (file_sha256)
    WHERE file_sha256 IS NOT NULL AND status = 'COMPLETED';

COMMENT ON COLUMN public.pg_reconciliation_runs.file_sha256 IS
    '업로드 파일 내용 SHA-256(hex) — 같은 파일 재업로드 멱등 키. 레거시 run 은 NULL.';
COMMENT ON INDEX public.uq_pg_recon_runs_file_sha256_completed IS
    '같은 파일로 COMPLETED run 1건만 — 재업로드 이중 clawback 차단(FAILED 는 재시도 허용).';
