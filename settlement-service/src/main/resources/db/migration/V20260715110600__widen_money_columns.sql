-- V20260715110600: 금액 컬럼 정밀도 NUMERIC(19,2) 통일 — DB 설계 리뷰 후속 (레인 E1)
--
-- 무엇을: settlements/settlement_adjustments/payouts/chargebacks/ledger_entries/ledger_outbox/
--         pg_reconciliation_discrepancies + 프로젝션 뷰(settlement_payment_view/settlement_order_view)의
--         모든 금액 컬럼을 NUMERIC(19,2) 로 확폭한다. 요율(rate)류(commission_rate/holdback_rate,
--         numeric(5,4))는 그대로 둔다.
-- 왜: V1 export 는 컬럼마다 (10,2)/(12,2)/(14,2)/(15,2)로 제각각이라 대형 거래·누적 합계에서 오버플로
--     위험과 표현 불일치가 있었다. 19,2 로 통일해 오버플로 여지를 없애고 서비스 전반 금액 표현을 맞춘다.
-- ★ ddl-auto=validate 안전: PG 에서 NUMERIC 정밀도 "확대"는 값 재작성(rewrite) 없이 메타데이터만 변경
--     되고, Hibernate 스키마 검증은 NUMERIC 컬럼의 precision/scale 을 비교하지 않으므로(엔티티가 10,2 로
--     매핑돼 있어도) 검증 실패가 없다. 확대만 하므로 잘림·정밀도 손실도 없다.

-- settlements (요율 2종은 제외)
ALTER TABLE public.settlements
    ALTER COLUMN payment_amount  TYPE numeric(19,2),
    ALTER COLUMN refunded_amount TYPE numeric(19,2),
    ALTER COLUMN commission      TYPE numeric(19,2),
    ALTER COLUMN net_amount      TYPE numeric(19,2),
    ALTER COLUMN holdback_amount TYPE numeric(19,2);

-- settlement_adjustments
ALTER TABLE public.settlement_adjustments
    ALTER COLUMN amount TYPE numeric(19,2);

-- payouts
ALTER TABLE public.payouts
    ALTER COLUMN amount TYPE numeric(19,2);

-- chargebacks
ALTER TABLE public.chargebacks
    ALTER COLUMN amount TYPE numeric(19,2);

-- ledger_entries
ALTER TABLE public.ledger_entries
    ALTER COLUMN amount TYPE numeric(19,2);

-- ledger_outbox (환불 금액)
ALTER TABLE public.ledger_outbox
    ALTER COLUMN refund_amount TYPE numeric(19,2);

-- pg_reconciliation_discrepancies (금액 3종 — difference 포함)
ALTER TABLE public.pg_reconciliation_discrepancies
    ALTER COLUMN internal_amount TYPE numeric(19,2),
    ALTER COLUMN pg_amount       TYPE numeric(19,2),
    ALTER COLUMN difference      TYPE numeric(19,2);

-- 프로젝션 뷰 (order 이벤트 적재 — 소비측 소유 테이블이라 확폭 안전)
ALTER TABLE public.settlement_payment_view
    ALTER COLUMN amount          TYPE numeric(19,2),
    ALTER COLUMN refunded_amount TYPE numeric(19,2);

ALTER TABLE public.settlement_order_view
    ALTER COLUMN amount TYPE numeric(19,2);

-- settlement_loan_deductions.deducted 는 이미 numeric(19,2) 라 대상 아님.
