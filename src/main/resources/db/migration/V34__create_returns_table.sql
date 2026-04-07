CREATE TABLE returns (
    id              BIGSERIAL      PRIMARY KEY,
    order_id        BIGINT         NOT NULL REFERENCES orders(id),
    user_id         BIGINT         NOT NULL REFERENCES users(id),
    type            VARCHAR(20)    NOT NULL CHECK (type IN ('RETURN', 'EXCHANGE')),
    status          VARCHAR(30)    NOT NULL DEFAULT 'REQUESTED',
    reason          VARCHAR(30)    NOT NULL,
    reason_detail   TEXT,
    refund_amount   NUMERIC(10,2),
    exchange_order_id BIGINT       REFERENCES orders(id),
    tracking_number VARCHAR(50),
    carrier         VARCHAR(50),
    approved_at     TIMESTAMP,
    received_at     TIMESTAMP,
    completed_at    TIMESTAMP,
    rejected_at     TIMESTAMP,
    rejection_reason TEXT,
    created_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_returns_order_id ON returns(order_id);
CREATE INDEX idx_returns_user_id ON returns(user_id);
CREATE INDEX idx_returns_status ON returns(status);
CREATE INDEX idx_returns_type ON returns(type);
