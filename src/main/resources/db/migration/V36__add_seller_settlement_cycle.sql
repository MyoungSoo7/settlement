-- V36: Seller settlement cycle + Settlement seller linkage

-- 1. Add settlement cycle columns to sellers
ALTER TABLE sellers
    ADD COLUMN IF NOT EXISTS settlement_cycle VARCHAR(10) NOT NULL DEFAULT 'DAILY',
    ADD COLUMN IF NOT EXISTS weekly_settlement_day VARCHAR(10),
    ADD COLUMN IF NOT EXISTS monthly_settlement_day INT,
    ADD COLUMN IF NOT EXISTS minimum_withdrawal_amount NUMERIC(12,2) NOT NULL DEFAULT 1000;

ALTER TABLE sellers ADD CONSTRAINT chk_monthly_day
    CHECK (monthly_settlement_day IS NULL OR (monthly_settlement_day >= 1 AND monthly_settlement_day <= 28));

-- 2. Add seller_id to settlements (nullable for backward compatibility)
ALTER TABLE settlements ADD COLUMN IF NOT EXISTS seller_id BIGINT REFERENCES sellers(id);
CREATE INDEX IF NOT EXISTS idx_settlements_seller_id ON settlements(seller_id);
