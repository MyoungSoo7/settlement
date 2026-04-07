CREATE TABLE product_options (
    id          BIGSERIAL    PRIMARY KEY,
    product_id  BIGINT       NOT NULL REFERENCES products(id),
    name        VARCHAR(50)  NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_product_option_name UNIQUE (product_id, name)
);

CREATE TABLE product_option_values (
    id          BIGSERIAL    PRIMARY KEY,
    option_id   BIGINT       NOT NULL REFERENCES product_options(id) ON DELETE CASCADE,
    value       VARCHAR(100) NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_option_value UNIQUE (option_id, value)
);

CREATE TABLE product_variants (
    id              BIGSERIAL      PRIMARY KEY,
    product_id      BIGINT         NOT NULL REFERENCES products(id),
    sku             VARCHAR(100)   NOT NULL UNIQUE,
    price           NUMERIC(10,2)  NOT NULL,
    stock_quantity  INT            NOT NULL DEFAULT 0,
    option_values   VARCHAR(500)   NOT NULL,
    is_active       BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_product_options_product_id ON product_options(product_id);
CREATE INDEX idx_product_option_values_option_id ON product_option_values(option_id);
CREATE INDEX idx_product_variants_product_id ON product_variants(product_id);
CREATE INDEX idx_product_variants_sku ON product_variants(sku);
