-- V21: 2026년 1월 주문/결제/정산 완료 시드 데이터 (310건)
-- 정산 완료(DONE/CONFIRMED) 데이터로 정산조회 대시보드 검증용

DO $$
DECLARE
    v_admin_id       BIGINT;
    v_user_ids       BIGINT[];
    v_product_ids    BIGINT[];
    v_product_prices DECIMAL[];
    v_user_id        BIGINT;
    v_product_id     BIGINT;
    v_price          DECIMAL(10,2);
    v_order_id       BIGINT;
    v_payment_id     BIGINT;
    v_created_at     TIMESTAMP;
    v_settled_status VARCHAR(20);
    i                INT;
    v_day            INT;
    v_hour           INT;
BEGIN

    -- 기존 시드 사용자/관리자 ID 조회
    SELECT id INTO v_admin_id FROM users WHERE email = 'seed_admin@test.com';
    SELECT ARRAY(SELECT id FROM users WHERE email LIKE 'seed_user%@test.com' ORDER BY id)
        INTO v_user_ids;
    SELECT ARRAY(SELECT id    FROM products ORDER BY id) INTO v_product_ids;
    SELECT ARRAY(SELECT price FROM products ORDER BY id) INTO v_product_prices;

    IF v_admin_id IS NULL THEN
        RAISE EXCEPTION '시드 관리자 계정이 없습니다. V17 마이그레이션을 먼저 실행하세요.';
    END IF;

    -- ─────────────────────────────────────────────────────────────
    -- 2026년 1월 1일 ~ 31일, 하루 10건, 총 310건
    -- 정산 완료(DONE) 270건 + 승인완료(CONFIRMED) 40건
    -- ─────────────────────────────────────────────────────────────
    FOR v_day IN 1..31 LOOP
        FOR i IN 1..10 LOOP

            -- 사용자/상품 라운드-로빈
            v_user_id    := v_user_ids   [((v_day * 10 + i - 1) % array_length(v_user_ids, 1))    + 1];
            v_product_id := v_product_ids[((v_day * 10 + i - 1) % array_length(v_product_ids, 1)) + 1];
            v_price      := v_product_prices[((v_day * 10 + i - 1) % array_length(v_product_prices, 1)) + 1];

            -- 시간대 분산 (오전 9시 ~ 오후 11시)
            v_hour       := 9 + ((v_day + i) % 14);
            v_created_at := TIMESTAMP '2026-01-01 00:00:00'
                            + make_interval(days => v_day - 1)
                            + make_interval(hours => v_hour)
                            + make_interval(mins  => (i * 7) % 60);

            -- 정산 상태: 마지막 5일치(27~31일)는 CONFIRMED, 나머지는 DONE
            v_settled_status := CASE WHEN v_day >= 27 THEN 'CONFIRMED' ELSE 'DONE' END;

            -- ── 주문 ──
            INSERT INTO orders (user_id, product_id, amount, status, created_at, updated_at)
            VALUES (v_user_id, v_product_id, v_price, 'PAID', v_created_at, v_created_at)
            RETURNING id INTO v_order_id;

            -- ── 결제 (CAPTURED) ──
            INSERT INTO payments (
                order_id, amount, refunded_amount, status,
                payment_method, pg_transaction_id,
                captured_at, created_at, updated_at
            ) VALUES (
                v_order_id, v_price, 0.00, 'CAPTURED',
                CASE ((v_day + i) % 4)
                    WHEN 0 THEN 'TOSS_PAYMENTS'
                    WHEN 1 THEN 'CARD'
                    WHEN 2 THEN 'BANK_TRANSFER'
                    ELSE        'VIRTUAL_ACCOUNT'
                END,
                'jan2026_txn_' || LPAD((v_day * 100 + i)::TEXT, 5, '0'),
                v_created_at + INTERVAL '1 minute',
                v_created_at,
                v_created_at + INTERVAL '1 minute'
            )
            RETURNING id INTO v_payment_id;

            -- ── 정산 (완료 처리) ──
            INSERT INTO settlements (
                payment_id, order_id,
                payment_amount, commission, net_amount,
                status, settlement_date,
                confirmed_at,
                approved_by, approved_at,
                rejected_by, rejected_at, rejection_reason,
                created_at, updated_at
            ) VALUES (
                v_payment_id,
                v_order_id,
                v_price,
                ROUND(v_price * 0.03, 2),
                ROUND(v_price * 0.97, 2),
                v_settled_status,
                -- 정산일: 결제 다음날
                (v_created_at + INTERVAL '1 day')::DATE,
                -- confirmed_at: DONE/CONFIRMED 모두 확정 처리
                v_created_at + INTERVAL '2 days',
                -- approved_by / approved_at
                v_admin_id,
                v_created_at + INTERVAL '1 day',
                -- rejected 없음
                NULL, NULL, NULL,
                v_created_at,
                v_created_at + INTERVAL '2 days'
            );

        END LOOP;
    END LOOP;

    RAISE NOTICE '2026년 1월 시드 데이터 삽입 완료: 주문+결제+정산 각 310건 (DONE 270건, CONFIRMED 40건)';

END $$;