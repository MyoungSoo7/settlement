-- 시연용 데이터 — Flyway 마이그레이션 V1~V43 + V17 시드 (seed_admin/seed_user) 위에 추가.
-- 모든 ID 는 9000+ 범위 사용 → 기존 시드와 충돌 없음.
-- 멱등 (ON CONFLICT DO NOTHING) — 여러 번 실행해도 안전.

BEGIN;

-- ───────────────────────────────────────────────
-- 1. 분할결제 시연용 — 이미 CAPTURED 인 Payment + Tenders
-- ───────────────────────────────────────────────

-- 시연용 주문 (id=9001, user_id=1=seed_admin, amount=50000, PAID)
INSERT INTO opslab.orders (id, user_id, product_id, amount, status, created_at, updated_at)
VALUES (9001, 1, 1, 50000, 'PAID', NOW() - INTERVAL '1 hour', NOW())
ON CONFLICT (id) DO NOTHING;

-- 시연용 분할결제 — POINT 5,000 + GIFT_CARD 10,000 + CARD 35,000 = 50,000
INSERT INTO opslab.payments (id, order_id, amount, refunded_amount, status, payment_method,
                             pg_transaction_id, captured_at, created_at, updated_at)
VALUES (9001, 9001, 50000, 0, 'CAPTURED', 'SPLIT:CARD',
        'SPLIT-9001', NOW() - INTERVAL '1 hour', NOW() - INTERVAL '1 hour', NOW())
ON CONFLICT (id) DO NOTHING;

-- 3 개 tender — sequence 순서대로 (역순 환불 시연용)
INSERT INTO opslab.payment_tenders (id, payment_id, tender_type, amount, refunded_amount,
                                    pg_transaction_id, status, sequence, created_at, updated_at)
VALUES
    (9001, 9001, 'POINT',     5000,  0, NULL,                'CAPTURED', 1, NOW(), NOW()),
    (9002, 9001, 'GIFT_CARD', 10000, 0, NULL,                'CAPTURED', 2, NOW(), NOW()),
    (9003, 9001, 'CARD',      35000, 0, 'TOSS:demo-9001',    'CAPTURED', 3, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- ───────────────────────────────────────────────
-- 2. SKU 동시성 시연용 — ProductVariant 3개
-- ───────────────────────────────────────────────

-- 시연용 상품 (id=9001) — 옵션 있는 티셔츠
INSERT INTO opslab.products (id, name, description, price, stock_quantity, status, created_at, updated_at)
VALUES (9001, '[DEMO] 티셔츠 (시연용)', '분할결제·동시성 시연 전용 상품',
        29000, 0, 'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- SKU 3 개
INSERT INTO opslab.product_variants (id, product_id, sku, option_name, additional_price,
                                     stock_quantity, version, status, created_at, updated_at)
VALUES
    (9001, 9001, 'DEMO-RED-S',  '색상:빨강/사이즈:S', 0,    50, 0, 'ACTIVE', NOW(), NOW()),
    (9002, 9001, 'DEMO-RED-L',  '색상:빨강/사이즈:L', 1000, 50, 0, 'ACTIVE', NOW(), NOW()),
    (9003, 9001, 'DEMO-BLUE-M', '색상:파랑/사이즈:M', 0,    30, 0, 'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- ───────────────────────────────────────────────
-- 3. Payout 시연용 — Holdback released 된 Settlement (Payout 즉시 가능)
-- ───────────────────────────────────────────────

-- Settlement (id=9001, payment_id=9001, DONE, holdback_released=true)
INSERT INTO opslab.settlements (id, payment_id, order_id, payment_amount, refunded_amount,
                                commission, commission_rate, net_amount, status,
                                settlement_date, holdback_amount, holdback_rate,
                                holdback_release_date, holdback_released, holdback_released_at,
                                version, created_at, updated_at, confirmed_at)
VALUES (9001, 9001, 9001, 50000, 0, 1750, 0.0350, 48250, 'DONE',
        CURRENT_DATE - INTERVAL '8 days',
        0, 0.30, CURRENT_DATE - INTERVAL '1 day', true, NOW(),
        1, NOW() - INTERVAL '8 days', NOW(), NOW() - INTERVAL '7 days')
ON CONFLICT (id) DO NOTHING;

-- 추가로 Payout 시연용 미해결 정산 2건 (FAILED 시뮬레이션 보여주기 위해)
INSERT INTO opslab.settlements (id, payment_id, order_id, payment_amount, refunded_amount,
                                commission, commission_rate, net_amount, status,
                                settlement_date, holdback_amount, holdback_rate,
                                holdback_release_date, holdback_released, holdback_released_at,
                                version, created_at, updated_at, confirmed_at)
VALUES
    (9002, NULL, NULL, 100000, 0, 3500, 0.0350, 96500, 'DONE',
     CURRENT_DATE - INTERVAL '8 days',
     0, 0.30, CURRENT_DATE - INTERVAL '1 day', true, NOW(),
     1, NOW() - INTERVAL '8 days', NOW(), NOW() - INTERVAL '7 days'),
    (9003, NULL, NULL, 230000, 0, 8050, 0.0350, 221950, 'DONE',
     CURRENT_DATE - INTERVAL '8 days',
     0, 0.30, CURRENT_DATE - INTERVAL '1 day', true, NOW(),
     1, NOW() - INTERVAL '8 days', NOW(), NOW() - INTERVAL '7 days')
ON CONFLICT (id) DO NOTHING;

-- Payout REQUESTED 3건 — 시연 시 execute-now 누르면 펌뱅킹 호출됨
INSERT INTO opslab.payouts (id, settlement_id, seller_id, amount,
                            bank_code, bank_account_number, account_holder_name,
                            status, retry_count, requested_at, created_at, updated_at)
VALUES
    (9001, 9001, 1, 48250,  'KB',      '110-123-456789', '데모셀러A', 'REQUESTED', 0, NOW(), NOW(), NOW()),
    (9002, 9002, 1, 96500,  'SHINHAN', '140-987-654321', '데모셀러B', 'REQUESTED', 0, NOW(), NOW(), NOW()),
    (9003, 9003, 1, 221950, 'WOORI',   '1002-345-67890', '데모셀러C', 'REQUESTED', 0, NOW(), NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- ───────────────────────────────────────────────
-- 4. DLQ 시연용 — FAILED outbox 이벤트 1 건 (운영자 retry 데모)
-- ───────────────────────────────────────────────

INSERT INTO opslab.outbox_events (id, aggregate_type, aggregate_id, event_type, event_id,
                                   payload, status, retry_count, last_error,
                                   created_at, published_at, trace_parent)
VALUES (9001, 'Payment', '9001', 'PaymentCaptured',
        '00000000-0000-0000-0000-000000009001'::uuid,
        '{"paymentId":9001,"orderId":9001,"amount":"50000"}',
        'FAILED', 10,
        '[DEMO] Kafka producer timeout — 운영자 retry 시연용 시드 데이터',
        NOW() - INTERVAL '30 minutes', NULL,
        '00-' || replace(gen_random_uuid()::text, '-', '') || '-' || substr(replace(gen_random_uuid()::text, '-', ''), 1, 16) || '-01')
ON CONFLICT (id) DO NOTHING;

-- ───────────────────────────────────────────────
-- 5. 시퀀스 충돌 방지 — 9000+ 까지 setval
-- ───────────────────────────────────────────────

SELECT setval('opslab.orders_id_seq',
              GREATEST(9001, COALESCE((SELECT MAX(id) FROM opslab.orders), 0)) + 1);
SELECT setval('opslab.payments_id_seq',
              GREATEST(9001, COALESCE((SELECT MAX(id) FROM opslab.payments), 0)) + 1);
SELECT setval('opslab.payment_tenders_id_seq',
              GREATEST(9003, COALESCE((SELECT MAX(id) FROM opslab.payment_tenders), 0)) + 1);
SELECT setval('opslab.products_id_seq',
              GREATEST(9001, COALESCE((SELECT MAX(id) FROM opslab.products), 0)) + 1);
SELECT setval('opslab.product_variants_id_seq',
              GREATEST(9003, COALESCE((SELECT MAX(id) FROM opslab.product_variants), 0)) + 1);
SELECT setval('opslab.settlements_id_seq',
              GREATEST(9003, COALESCE((SELECT MAX(id) FROM opslab.settlements), 0)) + 1);
SELECT setval('opslab.payouts_id_seq',
              GREATEST(9003, COALESCE((SELECT MAX(id) FROM opslab.payouts), 0)) + 1);
SELECT setval('opslab.outbox_events_id_seq',
              GREATEST(9001, COALESCE((SELECT MAX(id) FROM opslab.outbox_events), 0)) + 1);

COMMIT;

-- 확인 쿼리
SELECT '✅ 시드 완료 — 시연 자료:' AS msg;
SELECT 'Payment 9001 (CAPTURED, 50,000 = 5K POINT + 10K GIFT + 35K CARD)' AS demo_data
UNION ALL SELECT 'Variants 9001/9002/9003 (재고 50/50/30)'
UNION ALL SELECT 'Payouts 9001/9002/9003 (REQUESTED, 즉시 송금 가능)'
UNION ALL SELECT 'Outbox FAILED 9001 (DLQ 콘솔 시연용)';
