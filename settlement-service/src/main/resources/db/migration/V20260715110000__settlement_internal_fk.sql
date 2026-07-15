-- V20260715110000: settlement_db 내부 참조 무결성(FK) 복원 — DB 설계 리뷰 후속 (레인 E1)
--
-- 무엇을: 같은 DB(settlement_db, public 스키마)의 실테이블 간 참조를 외래키로 강제한다.
-- 왜: V1 베이스라인은 Hibernate schema-export 산출물이라 내부 FK 가 0건이었다. settlement 엔티티가
--     order/refund(payments·orders·users·products·refund)는 ID(Long)로만 참조하므로 Hibernate 가 FK 를
--     안 만든 것은 정상(cross-DB 경계 보존)이지만, settlement_db 내부의 부모-자식 참조까지 FK 가 없어
--     고아 행(존재하지 않는 settlement 를 가리키는 adjustment/payout 등)이 물리적으로 허용됐다.
--
-- ★ 경계 원칙: 아래 컬럼은 cross-DB(order-service) 참조이므로 FK 를 만들지 않는다(MSA 경계 100% 보존):
--     settlements.payment_id / order_id, chargebacks.payment_id,
--     settlement_adjustments.refund_id, ledger_outbox.refund_id,
--     pg_reconciliation_discrepancies.payment_id.
-- ★ 프로젝션 뷰(settlement_*_view)로의 FK 도 금지 — 이벤트 적재 타이밍상 정산 행이 뷰보다 먼저
--     존재할 수 있어(고아 참조가 정상 상태) FK 를 걸면 정상 흐름이 깨진다.
-- ★ ledger_entries.reference_id 는 FK 없음 — reference_type(SETTLEMENT|REFUND)으로 분기하는 다형
--     참조라 단일 부모 테이블을 못 가리키고, REFUND 는 cross-DB(order)라 애초에 FK 불가.
--
-- ON DELETE 정책: 전부 RESTRICT. 금융 데이터라 부모(settlements/runs/chargebacks/discrepancies)는
--   불변·append-only 로 물리 삭제되지 않는다(정산 DONE 불변 트리거·역분개 원칙). 따라서 RESTRICT 는
--   실무에서 발동하지 않는 방어선이며, 만에 하나 부모를 지우려 할 때 자식(자금 추적선)을 조용히
--   끊는 SET NULL/CASCADE 대신 삭제 자체를 거부해 원장·정산 추적성을 보전한다.
--
-- 안전성: 각 FK 는 NOT VALID 로 추가(기존 행 즉시 스캔 안 함) 후 VALIDATE(정합 확인). 빈 DB 는 즉시,
--   기존 DB 도 데이터가 일관하면(도메인 경로로만 적재됐으므로 고아 없음) 안전하게 통과한다.

-- 1) settlement_adjustments.settlement_id → settlements(id) : 조정은 반드시 실재 정산에 귀속
ALTER TABLE public.settlement_adjustments
    ADD CONSTRAINT fk_adjustments_settlement
    FOREIGN KEY (settlement_id) REFERENCES public.settlements (id)
    ON DELETE RESTRICT NOT VALID;
ALTER TABLE public.settlement_adjustments VALIDATE CONSTRAINT fk_adjustments_settlement;

-- 2) settlement_adjustments.chargeback_id → chargebacks(id) : 분쟁 연결(같은 DB, nullable)
ALTER TABLE public.settlement_adjustments
    ADD CONSTRAINT fk_adjustments_chargeback
    FOREIGN KEY (chargeback_id) REFERENCES public.chargebacks (id)
    ON DELETE RESTRICT NOT VALID;
ALTER TABLE public.settlement_adjustments VALIDATE CONSTRAINT fk_adjustments_chargeback;

-- 3) settlement_adjustments.reconciliation_discrepancy_id → pg_reconciliation_discrepancies(id)
--    PG 대사 clawback 연결(같은 DB, nullable)
ALTER TABLE public.settlement_adjustments
    ADD CONSTRAINT fk_adjustments_recon_discrepancy
    FOREIGN KEY (reconciliation_discrepancy_id) REFERENCES public.pg_reconciliation_discrepancies (id)
    ON DELETE RESTRICT NOT VALID;
ALTER TABLE public.settlement_adjustments VALIDATE CONSTRAINT fk_adjustments_recon_discrepancy;

-- 4) payouts.settlement_id → settlements(id) : nullable(정산 없는 수동 출금 여지). RESTRICT.
--    (nullable 컬럼의 FK 는 NULL 행을 검사하지 않으므로 무출처 payout 은 계속 허용된다.)
ALTER TABLE public.payouts
    ADD CONSTRAINT fk_payouts_settlement
    FOREIGN KEY (settlement_id) REFERENCES public.settlements (id)
    ON DELETE RESTRICT NOT VALID;
ALTER TABLE public.payouts VALIDATE CONSTRAINT fk_payouts_settlement;

-- 5) chargebacks.settlement_id → settlements(id) : 분쟁이 정산에 매칭되면 채워짐(nullable). RESTRICT.
ALTER TABLE public.chargebacks
    ADD CONSTRAINT fk_chargebacks_settlement
    FOREIGN KEY (settlement_id) REFERENCES public.settlements (id)
    ON DELETE RESTRICT NOT VALID;
ALTER TABLE public.chargebacks VALIDATE CONSTRAINT fk_chargebacks_settlement;

-- 6) ledger_outbox.settlement_id → settlements(id) : 원장 작업 대상 정산(NOT NULL). RESTRICT.
ALTER TABLE public.ledger_outbox
    ADD CONSTRAINT fk_ledger_outbox_settlement
    FOREIGN KEY (settlement_id) REFERENCES public.settlements (id)
    ON DELETE RESTRICT NOT VALID;
ALTER TABLE public.ledger_outbox VALIDATE CONSTRAINT fk_ledger_outbox_settlement;

-- 7) settlement_loan_deductions.settlement_id → settlements(id) : 정산 1:1 확장(PK=settlement_id). RESTRICT.
ALTER TABLE public.settlement_loan_deductions
    ADD CONSTRAINT fk_loan_deductions_settlement
    FOREIGN KEY (settlement_id) REFERENCES public.settlements (id)
    ON DELETE RESTRICT NOT VALID;
ALTER TABLE public.settlement_loan_deductions VALIDATE CONSTRAINT fk_loan_deductions_settlement;

-- 8) settlement_index_queue.settlement_id → settlements(id) : ES 재색인 재시도 큐(운영성). RESTRICT.
--    운영 데이터지만 실재 정산만 색인 대상이라 고아 큐 행을 물리 차단한다.
ALTER TABLE public.settlement_index_queue
    ADD CONSTRAINT fk_index_queue_settlement
    FOREIGN KEY (settlement_id) REFERENCES public.settlements (id)
    ON DELETE RESTRICT NOT VALID;
ALTER TABLE public.settlement_index_queue VALIDATE CONSTRAINT fk_index_queue_settlement;

-- 9) pg_reconciliation_discrepancies.run_id → pg_reconciliation_runs(id) : 불일치는 대사 실행의 자식(NOT NULL).
--    구성(composition) 관계라 CASCADE 도 자연스럽지만, 대사 실행 기록도 감사 대상 불변 이력이라
--    삭제되지 않으므로 RESTRICT 로 통일(부모 삭제 시도 자체를 거부).
ALTER TABLE public.pg_reconciliation_discrepancies
    ADD CONSTRAINT fk_recon_discrepancy_run
    FOREIGN KEY (run_id) REFERENCES public.pg_reconciliation_runs (id)
    ON DELETE RESTRICT NOT VALID;
ALTER TABLE public.pg_reconciliation_discrepancies VALIDATE CONSTRAINT fk_recon_discrepancy_run;
