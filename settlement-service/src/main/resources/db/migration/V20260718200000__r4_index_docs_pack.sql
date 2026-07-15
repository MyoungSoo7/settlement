-- V20260718200000: R4 리뷰 후속 팩 — 중복 인덱스 정리·저선택도 인덱스 교체·감사 PII 계약 (settlement_db)
--
-- [① 중복 인덱스] V1 idx_ledger_reference(reference_id, reference_type)는
--   V20260715110300 uq_ledger_reference_accounts(reference_id, reference_type, ...) 선두 2컬럼에
--   완전 포섭(유니크 인덱스가 접두 조회 커버) → DROP. order V20260718100000 과 대칭.
-- [② 저선택도 인덱스 교체] V1 idx_settlements_status(status 단일)는 DONE 편중 저선택도.
--   실조회는 status×settlement_date 축 — (status, settlement_date) 복합으로 교체(선두 status 가
--   status-only 조건도 커버, 회귀 없음).
-- [③ 감사 detail_json PII 계약] order 와 동일 — 마스킹은 기록기 단일 초크포인트 책임임을 계약으로
--   각인(컬럼 암호화 대신 검색 가능성 보존 + append-only 변조 차단 + 유입 전 마스킹).

-- ① 중복 인덱스 정리
DROP INDEX IF EXISTS public.idx_ledger_reference;

-- ② 저선택도 단일 인덱스 → 복합 교체
CREATE INDEX IF NOT EXISTS idx_settlements_status_date
    ON public.settlements (status, settlement_date);
DROP INDEX IF EXISTS public.idx_settlements_status;

-- ③ 감사 PII 마스킹 계약
COMMENT ON COLUMN public.audit_logs.detail_json IS
    '작업 상세(변경 전/후 값). PII 는 기록기 단일 초크포인트에서 마스킹 후 유입되어야 한다(평문 계좌·주민번호 금지). append-only 라 사후 정정 불가 — 유입 전 마스킹이 계약.';
