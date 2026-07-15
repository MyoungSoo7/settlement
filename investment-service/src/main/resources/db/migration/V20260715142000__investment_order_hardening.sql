-- V20260715142000: 투자주문 강건화 + Outbox/멱등 리텐션 (DB 설계 리뷰 반영, E4 레인)
--
-- 3인 DB 설계 리뷰 지적: investment_orders 는 updated_at 이 없어 상태 전이 시각 추적 불가,
-- outbox_events/processed_events 는 무한 적재된다. (상태 CHECK·amount>0 은 V1 에 이미 존재.)
--
-- 근거·제약:
--   * 컬럼 이름은 실제 스키마 대조: amount(NUMERIC 19,2, V1 에 CHECK(amount>0)=chk_investment_amount_positive
--     보유 → 재추가 안 함), status(V1 에 chk_investment_status 로 REQUESTED/APPROVED/EXECUTED/REJECTED/CANCELED
--     = InvestmentOrderStatus enum 과 일치 보유 → 재추가 안 함), score_at_order, created_at.
--     ★ 수량(quantity) 컬럼은 스키마에 없음 → "수량 CHECK" 는 대상 없음(추가하지 않음).
--   * 기존 V1~V3 이 스키마 미수식으로 생성했고 JPA validate 가 그 위에서 통과하므로 본 마이그레이션도 미수식.
--   * CHECK 는 NOT VALID → VALIDATE 2단계.

-- 투자점수 스냅샷 범위 방어(0~100) — V1 컬럼 주석의 도메인 불변식(신청 시점 투자점수 0~100)을 스키마로 고정.
-- corporate_loans.credit_score BETWEEN 0 AND 100 선례와 동형.
ALTER TABLE investment_orders
    ADD CONSTRAINT chk_investment_score_range CHECK (score_at_order BETWEEN 0 AND 100) NOT VALID;
ALTER TABLE investment_orders VALIDATE CONSTRAINT chk_investment_score_range;

-- updated_at 신설 + DB 유지 트리거.
--   근거 주석(dead column 아님): 투자주문은 상태머신(REQUESTED→APPROVED→EXECUTED/CANCELED)으로 행을
--   UPDATE 하지만 마지막 전이 시각을 추적할 컬럼이 없었다(리뷰 지적). JPA 엔티티(InvestmentOrderJpaEntity)는
--   이 컬럼을 매핑하지 않으므로(ddl-auto=validate 는 미매핑 추가 컬럼을 문제삼지 않음), 애플리케이션이
--   값을 쓰지 않아도 BEFORE UPDATE 트리거가 매 전이마다 NOW() 로 갱신해 실제 감사 가치를 갖게 한다.
--   NULL 허용 + DEFAULT NOW() 로 두어 엔티티의 INSERT(미매핑 컬럼 제외)에서도 기본값이 채워지게 한다.
-- 3단계: ① 널 허용 컬럼 신설 → ② 기존 행을 created_at 으로 백필(마이그레이션 시각 아님) → ③ 이후
-- INSERT 용 DEFAULT NOW() 부여. 백필이 DEFAULT 보다 앞서야 기존 행이 생성 시각을 반영한다.
ALTER TABLE investment_orders
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;

UPDATE investment_orders SET updated_at = created_at WHERE updated_at IS NULL;

ALTER TABLE investment_orders ALTER COLUMN updated_at SET DEFAULT NOW();

CREATE OR REPLACE FUNCTION touch_investment_order_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_investment_order_updated_at ON investment_orders;
CREATE TRIGGER trg_investment_order_updated_at
    BEFORE UPDATE ON investment_orders
    FOR EACH ROW
    EXECUTE FUNCTION touch_investment_order_updated_at();

COMMENT ON FUNCTION touch_investment_order_updated_at() IS
    'investment_orders 상태 전이(UPDATE)마다 updated_at 을 NOW() 로 DB 레벨 갱신(엔티티 미매핑 컬럼).';

-- (인덱스 보강 없음: sumBySellerAndStatus·findBySellerIdOrderByIdAsc 는 기존
--  idx_investment_orders_seller_status(seller_id,status,id) 로 이미 커버.)

-- ─────────────────────────────────────────────────────────────────────────────
-- Outbox/멱등 리텐션 함수 + PUBLISHED 정리 인덱스 (order-service E2 레인과 함수명·시그니처 통일)
-- ─────────────────────────────────────────────────────────────────────────────

-- PUBLISHED Outbox 행 리텐션(published_at 기준, 기본 7일). 반환: 삭제 건수. (loan V20260715140000 과 동형.)
CREATE OR REPLACE FUNCTION prune_outbox_published(p_retention INTERVAL DEFAULT INTERVAL '7 days')
RETURNS BIGINT AS $$
DECLARE
    v_deleted BIGINT;
BEGIN
    DELETE FROM outbox_events
        WHERE status = 'PUBLISHED'
          AND published_at IS NOT NULL
          AND published_at < NOW() - p_retention;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION prune_outbox_published(INTERVAL) IS
    'PUBLISHED Outbox 행 리텐션 정리(published_at 기준, 기본 7일). 삭제 건수 반환.';

-- 컨슈머 멱등 추적 행 리텐션(processed_at 기준, 기본 30일). 반환: 삭제 건수.
CREATE OR REPLACE FUNCTION prune_processed_events(p_retention INTERVAL DEFAULT INTERVAL '30 days')
RETURNS BIGINT AS $$
DECLARE
    v_deleted BIGINT;
BEGIN
    DELETE FROM processed_events
        WHERE processed_at < NOW() - p_retention;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION prune_processed_events(INTERVAL) IS
    'processed_events 멱등 추적 행 리텐션 정리(processed_at 기준, 기본 30일). 삭제 건수 반환.';

-- prune_outbox_published 스캔 최적화: 기존 idx_investment_outbox_status_created 는 PENDING/FAILED 만 커버.
CREATE INDEX IF NOT EXISTS idx_investment_outbox_published_at
    ON outbox_events (published_at)
    WHERE status = 'PUBLISHED';

-- prune_processed_events 는 기존 idx_investment_processed_events_processed_at(processed_at) 를 사용 — 신규 불요.
