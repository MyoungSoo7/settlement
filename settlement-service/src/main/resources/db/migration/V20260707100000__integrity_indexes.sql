-- Integrity Suite Phase A (docs/design/settlement-integrity-suite.md §3.1)
-- /admin/integrity/* 집계 쿼리용 인덱스. 기존 인덱스와의 중복 없음:
--   ledger_entries(reference_id, reference_type) 은 V1 idx_ledger_reference 가 이미 커버.
--   payouts(settlement_id) 는 V1 uq_payouts_settlement (partial unique) 가 커버.

-- 확정일 기준 대사 (ledger-completeness / payout-recon 의 날짜 스코프)
CREATE INDEX IF NOT EXISTS idx_settlements_confirmed_at
    ON public.settlements (confirmed_at)
    WHERE confirmed_at IS NOT NULL;

-- 해제 기한 경과 홀드백 스캔 (holdback-status) — 미해제 보류 건만 부분 인덱스
CREATE INDEX IF NOT EXISTS idx_settlements_holdback_due
    ON public.settlements (holdback_release_date)
    WHERE holdback_released = false AND holdback_amount > 0;
