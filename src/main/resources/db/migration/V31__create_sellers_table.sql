CREATE TABLE sellers (
    id                  BIGSERIAL      PRIMARY KEY,
    user_id             BIGINT         NOT NULL UNIQUE REFERENCES users(id),
    business_name       VARCHAR(200)   NOT NULL,
    business_number     VARCHAR(20)    NOT NULL UNIQUE,
    representative_name VARCHAR(100)   NOT NULL,
    phone               VARCHAR(20)    NOT NULL,
    email               VARCHAR(255)   NOT NULL,
    bank_name           VARCHAR(50),
    bank_account_number VARCHAR(50),
    bank_account_holder VARCHAR(100),
    commission_rate     NUMERIC(5,4)   NOT NULL DEFAULT 0.0300,
    status              VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    approved_at         TIMESTAMP,
    created_at          TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP      NOT NULL DEFAULT NOW()
);

ALTER TABLE products ADD COLUMN IF NOT EXISTS seller_id BIGINT REFERENCES sellers(id);

CREATE INDEX idx_sellers_user_id ON sellers(user_id);
CREATE INDEX idx_sellers_status ON sellers(status);
CREATE INDEX idx_sellers_business_number ON sellers(business_number);
CREATE INDEX idx_products_seller_id ON products(seller_id);
