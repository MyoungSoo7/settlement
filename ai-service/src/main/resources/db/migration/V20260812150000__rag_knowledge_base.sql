-- V20260812150000: RAG 지식베이스 — pgvector 기반 문서·청크·임베딩 (ADR 0034)
--
-- 설계 근거:
--   Phase 1 챗봇은 LLM 의 사전학습 지식만으로 답한다. 그래서 "우리 플랫폼의 정산 주기가 며칠인가"
--   같은 질문에 system prompt 에 손으로 박아 넣은 문장 이상을 답할 수 없고, 프롬프트에 없는 것은
--   지어내지 말라고 지시하는 것이 유일한 안전장치였다. RAG(검색증강생성)는 이 한계를
--   *프롬프트 확장*이 아니라 *검색*으로 푼다 — 질문을 임베딩해 유사한 문서 조각을 찾아 근거로 붙인다.
--
--   벡터 저장소를 새로 도입하지 않고 pgvector 를 쓴다: ai-service 는 이미 자체 DB(lemuel_ai)를
--   소유하고, 그 인스턴스(ai-postgres)의 이미지가 pgvector/pgvector:pg17 이라 확장만 켜면 된다.
--   전용 벡터 DB 한 대를 더 운영하는 비용(백업·모니터링·장애 축)이 이 규모에서 정당화되지 않는다.
--
-- ★ 왜 settlement-service 가 아니라 여기인가 (중요):
--   CREATE EXTENSION 은 슈퍼유저 권한을 요구한다. 운영 jen-postgres 의 settlement 앱 계정은
--   슈퍼유저가 아니라서, 같은 DDL 을 settlement-service 마이그레이션에 넣으면 2026-08-11 의
--   `permission denied to create extension` → 마이그레이션 체인 중단 → CrashLoopBackOff
--   (V20260807130000 주석 참조)를 그대로 재현한다. 반면 lemuel_ai 의 앱 계정은 슈퍼유저이고
--   vector 확장은 이미 설치돼 있어 아래 CREATE EXTENSION 은 안전한 no-op 이다.
--
-- ★ pg_stat_statements 와 달리 실패를 경고로 낮추지 않는다:
--   그 확장은 관측용이라 없어도 로직이 돌지만, vector 는 아래 DDL 의 컬럼 타입이다.
--   조용히 건너뛰면 다음 문장이 `type "vector" does not exist` 로 죽어 원인 추적이 어려워진다.
--   그래서 원인을 특정한 예외로 바꿔 던진다 — 실패는 하되 진단 가능하게.

DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS vector;
EXCEPTION
    WHEN insufficient_privilege THEN
        RAISE EXCEPTION 'pgvector 확장 생성 권한이 없습니다 — 앱 계정이 슈퍼유저가 아닌 DB 입니다'
            USING HINT = '슈퍼유저로 이 DB 에 접속해 CREATE EXTENSION vector; 를 먼저 실행한 뒤 재배포할 것 '
                         '(vector 는 trusted 확장이 아니므로 일반 계정에 위임할 수 없다)';
    WHEN undefined_file THEN
        RAISE EXCEPTION 'pgvector 확장 파일이 없습니다 — pgvector 미포함 PostgreSQL 이미지입니다'
            USING HINT = '이미지를 pgvector/pgvector:pg17 로 교체할 것 (compose 의 ai-postgres 는 이미 적용됨). '
                         'Testcontainers 도 postgres:17-alpine 이 아니라 pgvector 이미지를 써야 한다';
END
$$;

-- 지식 문서 — 임베딩 원본 1건. source_uri 가 자연키(같은 출처를 다시 넣으면 교체).
CREATE TABLE knowledge_documents (
    id            UUID         PRIMARY KEY,            -- 서버 생성
    title         VARCHAR(200) NOT NULL,               -- 프롬프트에 인용될 자료명 (근거 표기용)
    source_uri    VARCHAR(500) NOT NULL,               -- 출처 식별자 (문서 경로·URL·논리 키)
    content_hash  CHAR(64)     NOT NULL,               -- 마스킹 후 본문의 SHA-256 hex
    chunk_count   INTEGER      NOT NULL DEFAULT 0,
    ingested_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- 같은 출처는 1행. 재적재는 UPDATE + 청크 전량 교체로 처리한다.
    CONSTRAINT uq_knowledge_documents_source UNIQUE (source_uri)
);

-- content_hash 는 "내용이 안 바뀌었으면 재임베딩하지 않는다"의 판정 키다.
-- 임베딩은 외부 유료 API 호출이라, 같은 문서를 다시 넣는 실수의 비용을 여기서 0 으로 만든다.
COMMENT ON COLUMN knowledge_documents.content_hash IS '마스킹된 본문의 SHA-256 — 동일하면 재임베딩 스킵';

-- 검색 단위 청크 + 임베딩.
--
-- ★ 차원을 768 로 고정한 이유:
--   Gemini 임베딩 모델의 기본 출력은 3072 차원이다. 그런데 pgvector 의 vector 타입은
--   *인덱스를 걸 수 있는 상한이 2000 차원*이다(공식 README). 즉 3072 를 그대로 넣으면
--   테이블은 만들어지지만 HNSW/IVFFlat 인덱스를 못 만들어 매 질의가 전량 스캔이 된다.
--   gemini-embedding-001 은 MRL 로 학습돼 output_dimensionality 로 잘라 써도 되며 문서가
--   768/1536/3072 을 권장한다 — 그중 인덱스 가능한 768 을 택했다.
--   (대안: halfvec(3072) 은 4000 차원까지 인덱스 가능. ADR 0034 「대안 검토」 참조)
--
--   ※ 이 숫자는 컬럼 타입에 박혀 있으므로 *마이그레이션이 차원의 단일 진실*이다.
--     설정(app.ai.rag.embedding-dimension)만 바꾸면 INSERT 가 차원 불일치로 거부된다(의도된 동작).
--     차원을 바꾸려면 새 마이그레이션 + 전량 재임베딩이 필요하다.
CREATE TABLE knowledge_chunks (
    id              BIGSERIAL    PRIMARY KEY,
    document_id     UUID         NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    chunk_index     INTEGER      NOT NULL,             -- 문서 내 순서 (0부터)
    content         TEXT         NOT NULL,             -- 프롬프트에 실제로 실리는 텍스트
    embedding       vector(768)  NOT NULL,
    embedding_model VARCHAR(60)  NOT NULL,             -- 이 벡터를 만든 모델 id
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_knowledge_chunks_doc_index UNIQUE (document_id, chunk_index)
);

-- ★ embedding_model 을 청크마다 남기는 이유 (조용한 오답 차단):
--   모델을 바꾸면 벡터 공간이 바뀐다. 새 모델로 임베딩한 질문을 옛 모델의 청크와 비교하면
--   거리 계산은 성공하고 숫자도 그럴듯하게 나오지만 의미는 무작위다 — 즉 *에러 없이 오답*이 된다.
--   검색 SQL 이 embedding_model 로 필터하므로, 모델 교체 시 매칭 0건이 되어
--   "근거 없이 지어내지 않는" 기존 동작으로 안전하게 되돌아간다(재임베딩 전까지).
COMMENT ON COLUMN knowledge_chunks.embedding_model IS '임베딩 생성 모델 — 검색 시 동일 모델만 비교(벡터 공간 혼용 차단)';

-- 근사 최근접 인덱스. 연산자 클래스는 조회에 쓰는 거리 연산자와 짝이 맞아야 인덱스를 탄다:
--   vector_cosine_ops ↔ <=> (코사인 거리). L2 는 vector_l2_ops ↔ <->.
-- HNSW 기본 파라미터(m=16, ef_construction=64)를 쓴다 — 데이터가 수천 청크 규모라
-- 기본값에서 벗어날 근거가 없다. 재현율이 부족하면 조회 세션에서 hnsw.ef_search 를 올린다(기본 40).
CREATE INDEX idx_knowledge_chunks_embedding_hnsw
    ON knowledge_chunks USING hnsw (embedding vector_cosine_ops);

-- 문서 단위 조회·교체(재적재 시 DELETE)용
CREATE INDEX idx_knowledge_chunks_document ON knowledge_chunks (document_id, chunk_index);

-- 검색 SQL 의 embedding_model 필터를 위한 보조 인덱스.
-- (HNSW 는 후필터링이라 모델이 여러 벌 공존하는 과도기에만 의미가 있다 — 단일 모델이면 무해한 여분)
CREATE INDEX idx_knowledge_chunks_model ON knowledge_chunks (embedding_model);

COMMENT ON TABLE knowledge_documents IS 'RAG 지식 문서 원본 메타 — source_uri 자연키, content_hash 로 재임베딩 스킵 판정 (ADR 0034)';
COMMENT ON TABLE knowledge_chunks IS 'RAG 검색 단위 청크 + pgvector 임베딩(768차원, HNSW/코사인). 모델별 분리 비교 (ADR 0034)';
