CREATE TABLE carts (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id),
    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_cart_active_user UNIQUE (user_id, status)
);

CREATE TABLE cart_items (
    id              BIGSERIAL      PRIMARY KEY,
    cart_id         BIGINT         NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
    product_id      BIGINT         NOT NULL REFERENCES products(id),
    quantity        INT            NOT NULL DEFAULT 1 CHECK (quantity > 0),
    price_snapshot  NUMERIC(10,2)  NOT NULL,
    created_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_cart_item_product UNIQUE (cart_id, product_id)
);

CREATE INDEX idx_carts_user_id ON carts(user_id);
CREATE INDEX idx_cart_items_cart_id ON cart_items(cart_id);
CREATE INDEX idx_cart_items_product_id ON cart_items(product_id);
