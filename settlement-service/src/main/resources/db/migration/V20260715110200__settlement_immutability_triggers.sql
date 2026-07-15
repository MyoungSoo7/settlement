-- V20260715110200: 종료 상태 불변(immutability) 트리거 복원 — DB 설계 리뷰 후속 (레인 E1)
--
-- 무엇을: (1) DONE 정산의 금액·상태 UPDATE 차단, (2) POSTED/REVERSED 원장 항목의 금액·계정·참조
--         변경 및 DELETE 차단(POSTED→REVERSED 전이만 허용).
-- 왜: order-service 는 V30(settlements)로 이미 DB 레벨 불변을 갖지만, settlement_db 는 트리거가 없어
--     애플리케이션 버그가 지급 완료 정산·전기 완료 원장을 물리적으로 덮어쓸 수 있었다. 복식부기와
--     자금 추적선을 코드가 아니라 DB 가 최종 방어한다.

-- ───────────────── settlements: DONE 불변 (order V30 동형, public 스키마) ─────────────────
-- 홀드백 해제(holdback_released/holdback_released_at)는 DONE 이후에도 갱신되므로 가드에서 제외한다.
-- (V30 과 동일하게 payment_amount/commission/net_amount/refunded_amount/status 만 잠근다 — 환불은
--  settlement_adjustments 음수 row 로만 반영해 원장이 무너지지 않게 한다.)
CREATE OR REPLACE FUNCTION public.enforce_settlement_immutability()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status = 'DONE' THEN
        IF NEW.payment_amount  IS DISTINCT FROM OLD.payment_amount
           OR NEW.commission      IS DISTINCT FROM OLD.commission
           OR NEW.net_amount      IS DISTINCT FROM OLD.net_amount
           OR NEW.refunded_amount IS DISTINCT FROM OLD.refunded_amount
           OR NEW.status          IS DISTINCT FROM OLD.status THEN
            RAISE EXCEPTION
                'Settlement id=% is DONE (immutable). Financial fields cannot be modified; use settlement_adjustments instead.',
                OLD.id
                USING ERRCODE = '23514';  -- check_violation
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_settlements_immutability ON public.settlements;
CREATE TRIGGER trg_settlements_immutability
    BEFORE UPDATE ON public.settlements
    FOR EACH ROW
    EXECUTE FUNCTION public.enforce_settlement_immutability();

COMMENT ON FUNCTION public.enforce_settlement_immutability() IS
    'DONE 상태 정산의 금액·상태 컬럼 UPDATE 차단(홀드백 해제는 허용). 환불은 settlement_adjustments 로만 기록.';

-- ───────────────── ledger_entries: POSTED/REVERSED 불변 + DELETE 차단 ─────────────────
-- 전기(POSTED) 후에는 금액·계정·참조·분개유형이 불변이고, 상태는 REVERSED 로만 전이(역분개)한다.
-- REVERSED 는 종결 — 금융 필드 변경·상태 이탈 모두 금지. POSTED/REVERSED 행의 DELETE 는 감사 이력
-- 파기이므로 차단(정정은 신규 REVERSED 분개 작성으로만). PENDING(미전기) 행은 UPDATE/DELETE 자유.
-- memo·updated_at·posted_at 등 비금융 필드 갱신은 허용(POSTED→REVERSED 전기 시각 기록 등).
CREATE OR REPLACE FUNCTION public.enforce_ledger_immutability()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status IN ('POSTED','REVERSED') THEN
            RAISE EXCEPTION
                'LedgerEntry id=% is % (immutable audit record). DELETE is forbidden; write a REVERSED entry instead.',
                OLD.id, OLD.status
                USING ERRCODE = '23514';
        END IF;
        RETURN OLD;
    END IF;

    -- UPDATE
    IF OLD.status IN ('POSTED','REVERSED') THEN
        IF NEW.amount         IS DISTINCT FROM OLD.amount
           OR NEW.debit_account  IS DISTINCT FROM OLD.debit_account
           OR NEW.credit_account IS DISTINCT FROM OLD.credit_account
           OR NEW.reference_id   IS DISTINCT FROM OLD.reference_id
           OR NEW.reference_type IS DISTINCT FROM OLD.reference_type
           OR NEW.entry_type     IS DISTINCT FROM OLD.entry_type THEN
            RAISE EXCEPTION
                'LedgerEntry id=% is % (immutable). Amount/account/reference cannot change; write a REVERSED entry instead.',
                OLD.id, OLD.status
                USING ERRCODE = '23514';
        END IF;
        -- 허용 전이: POSTED→REVERSED 뿐. REVERSED 는 상태 이탈 불가.
        IF OLD.status = 'POSTED' AND NEW.status NOT IN ('POSTED','REVERSED') THEN
            RAISE EXCEPTION
                'LedgerEntry id=% is POSTED; only transition to REVERSED is allowed (got %).',
                OLD.id, NEW.status
                USING ERRCODE = '23514';
        END IF;
        IF OLD.status = 'REVERSED' AND NEW.status IS DISTINCT FROM OLD.status THEN
            RAISE EXCEPTION
                'LedgerEntry id=% is REVERSED (terminal). Status cannot change.',
                OLD.id
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_ledger_immutability ON public.ledger_entries;
CREATE TRIGGER trg_ledger_immutability
    BEFORE UPDATE OR DELETE ON public.ledger_entries
    FOR EACH ROW
    EXECUTE FUNCTION public.enforce_ledger_immutability();

COMMENT ON FUNCTION public.enforce_ledger_immutability() IS
    'POSTED/REVERSED 원장 항목의 금액·계정·참조 변경 및 DELETE 차단. POSTED→REVERSED 전이만 허용.';
