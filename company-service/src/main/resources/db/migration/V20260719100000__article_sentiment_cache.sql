-- 기사 감성 분석 결과 캐시 (ADR 0023 Phase 4 — LLM 비용 절감).
-- 평판 재계산은 30일 윈도우 전체 기사를 매번 분석했다(재계산마다 LLM 재호출 = 낭비).
-- 기사(title/summary)·url_hash 는 불변이라 (url_hash, provider) 별 감성은 영구 유효 →
-- 여기 캐시해 재계산 반복 시 새 기사만 분석한다(일일 LLM 호출량 급감).
-- provider 를 키에 포함: 엔진 전환(keyword↔gemini↔llm) 시 캐시가 섞이지 않고 새로 분석된다.
CREATE TABLE IF NOT EXISTS article_sentiment (
    url_hash    VARCHAR(64) NOT NULL REFERENCES articles (url_hash) ON DELETE CASCADE,
    provider    VARCHAR(20) NOT NULL,
    sentiment   VARCHAR(10) NOT NULL,
    category    VARCHAR(20),
    analyzed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_article_sentiment PRIMARY KEY (url_hash, provider),
    CONSTRAINT chk_article_sentiment_sentiment
        CHECK (sentiment IN ('POSITIVE', 'NEGATIVE', 'NEUTRAL')),
    CONSTRAINT chk_article_sentiment_category
        CHECK (category IS NULL OR category IN ('FINANCIAL', 'LEGAL', 'GOVERNANCE', 'LABOR', 'PRODUCT')),
    -- 도메인 불변식 정합: 이슈 카테고리는 부정 기사에만 (ArticleSentiment 와 동일)
    CONSTRAINT chk_article_sentiment_negcat
        CHECK (category IS NULL OR sentiment = 'NEGATIVE')
);
