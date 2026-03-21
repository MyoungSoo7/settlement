-- pgvector 확장 활성화
CREATE EXTENSION IF NOT EXISTS vector;

-- 문서 임베딩 테이블
CREATE TABLE opslab.document_embedding (
    id          BIGSERIAL PRIMARY KEY,
    entity_type VARCHAR(50)  NOT NULL,
    entity_id   BIGINT       NOT NULL,
    content     TEXT         NOT NULL,
    embedding   vector(1536) NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_document_embedding UNIQUE (entity_type, entity_id)
);

-- IVFFlat 인덱스 (코사인 유사도)
CREATE INDEX idx_embedding_vector ON opslab.document_embedding
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

CREATE INDEX idx_embedding_entity ON opslab.document_embedding (entity_type, entity_id);

-- 대화 히스토리 테이블
CREATE TABLE opslab.conversation (
    id         BIGSERIAL    PRIMARY KEY,
    session_id VARCHAR(64)  NOT NULL,
    role       VARCHAR(20)  NOT NULL,
    content    TEXT         NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_conversation_session ON opslab.conversation (session_id, created_at);
