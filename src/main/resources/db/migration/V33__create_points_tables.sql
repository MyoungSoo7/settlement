CREATE TABLE points (
    id          BIGSERIAL      PRIMARY KEY,
    user_id     BIGINT         NOT NULL UNIQUE REFERENCES users(id),
    balance     NUMERIC(12,2)  NOT NULL DEFAULT 0 CHECK (balance >= 0),
    total_earned NUMERIC(12,2) NOT NULL DEFAULT 0,
    total_used  NUMERIC(12,2)  NOT NULL DEFAULT 0,
    created_at  TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE TABLE point_transactions (
    id              BIGSERIAL      PRIMARY KEY,
    user_id         BIGINT         NOT NULL REFERENCES users(id),
    point_id        BIGINT         NOT NULL REFERENCES points(id),
    type            VARCHAR(20)    NOT NULL,
    amount          NUMERIC(12,2)  NOT NULL,
    balance_after   NUMERIC(12,2)  NOT NULL,
    description     VARCHAR(500),
    reference_type  VARCHAR(30),
    reference_id    BIGINT,
    expires_at      TIMESTAMP,
    created_at      TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_points_user_id ON points(user_id);
CREATE INDEX idx_point_transactions_user_id ON point_transactions(user_id);
CREATE INDEX idx_point_transactions_point_id ON point_transactions(point_id);
CREATE INDEX idx_point_transactions_type ON point_transactions(type);
CREATE INDEX idx_point_transactions_reference ON point_transactions(reference_type, reference_id);
