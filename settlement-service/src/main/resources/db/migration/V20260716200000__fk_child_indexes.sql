-- V20260716200000: 내부 FK 자식 컬럼 인덱스 3종 — DB 설계 리뷰 R2 후속 (레인 F2)
--
-- 무엇을: V20260715110000 에서 추가된 settlement_db 내부 FK 중, 자식 컬럼에 인덱스가 없어
--         (1) RESTRICT 부모 삭제 시 자식 전수 스캔, (2) 부모→자식 역참조 조인이 순차 스캔이 되는
--         3개 컬럼에 btree 인덱스를 보강한다.
--           - chargebacks.settlement_id            (FK fk_chargebacks_settlement)
--           - ledger_outbox.settlement_id          (FK fk_ledger_outbox_settlement)
--           - settlement_index_queue.settlement_id (FK fk_index_queue_settlement)
-- 왜: PostgreSQL 은 FK 부모측(settlements.id, PK)에만 인덱스를 자동 생성하고 자식측(참조 컬럼)에는
--     만들지 않는다. 자식 인덱스가 없으면 부모 행 삭제 검사(RESTRICT 확인)와 "이 정산의 chargeback/
--     outbox 작업/색인 큐" 같은 역참조 조회가 매번 자식 테이블 전체를 훑는다.
--
-- ★ 기존 인덱스와의 중복 회피 확인(V1 베이스라인 대조):
--     - chargebacks            : idx_chargebacks_pg_id_unique(pg_chargeback_id) / idx_chargebacks_status_raised
--                                (status, raised_at) 뿐 — settlement_id 선두 인덱스 없음 → 신규.
--     - ledger_outbox          : idx_ledger_outbox_poll(status, id) 뿐 — settlement_id 선두 인덱스 없음 → 신규.
--     - settlement_index_queue : idx_settlement_index_queue_status(status) 뿐 — settlement_id 선두 인덱스 없음 → 신규.
--   (참고: 이미 인덱스가 있어 이번 대상에서 제외한 FK 자식 —
--     settlement_adjustments.settlement_id=idx_adjustments_settlement_id,
--     payouts.settlement_id=uq_payouts_settlement(부분 유니크),
--     settlement_loan_deductions.settlement_id=PK,
--     pg_reconciliation_discrepancies.run_id=idx_pg_recon_discrepancy_run.)
--
-- 안전성: 순수 인덱스 추가(IF NOT EXISTS)라 재실행 안전. 컬럼 정의 변경 없음(ddl-auto=validate 무영향).

CREATE INDEX IF NOT EXISTS idx_chargebacks_settlement_id
    ON public.chargebacks (settlement_id);

CREATE INDEX IF NOT EXISTS idx_ledger_outbox_settlement_id
    ON public.ledger_outbox (settlement_id);

CREATE INDEX IF NOT EXISTS idx_settlement_index_queue_settlement_id
    ON public.settlement_index_queue (settlement_id);

COMMENT ON INDEX public.idx_chargebacks_settlement_id IS
    'FK fk_chargebacks_settlement 자식 인덱스 — RESTRICT 부모 삭제 검사·정산별 분쟁 역참조 조인 가속.';
COMMENT ON INDEX public.idx_ledger_outbox_settlement_id IS
    'FK fk_ledger_outbox_settlement 자식 인덱스 — RESTRICT 부모 삭제 검사·정산별 원장 작업 역참조 가속.';
COMMENT ON INDEX public.idx_settlement_index_queue_settlement_id IS
    'FK fk_index_queue_settlement 자식 인덱스 — RESTRICT 부모 삭제 검사·정산별 재색인 큐 역참조 가속.';
