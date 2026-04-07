CREATE TABLE stock_reservations (
    id          BIGSERIAL    PRIMARY KEY,
    product_id  BIGINT       NOT NULL REFERENCES products(id),
    order_id    BIGINT,
    user_id     BIGINT       NOT NULL REFERENCES users(id),
    quantity    INT          NOT NULL CHECK (quantity > 0),
    status      VARCHAR(20)  NOT NULL DEFAULT 'RESERVED',
    expires_at  TIMESTAMP    NOT NULL,
    confirmed_at TIMESTAMP,
    released_at  TIMESTAMP,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_stock_reservations_product_id ON stock_reservations(product_id);
CREATE INDEX idx_stock_reservations_user_id ON stock_reservations(user_id);
CREATE INDEX idx_stock_reservations_status ON stock_reservations(status);
CREATE INDEX idx_stock_reservations_expires_at ON stock_reservations(expires_at) WHERE status = 'RESERVED';
