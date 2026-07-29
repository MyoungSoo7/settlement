-- 일일 스크리닝 실행 기록 — "이 시세 기준일로 이미 돌았는가"의 정본.
--
-- 왜 추천 테이블로는 안 되는가: 스크리닝이 통과 종목 0건을 내면 stock_recommendations 에 아무 행도
-- 남지 않아 그 기준일이 사라진다. 그러면 다음 실행이 "미처리"로 오판해 같은 기준일을 매일 재스크리닝하고,
-- 휴장일 스킵(ScreeningTriggerPolicy)이 빈 세트인 경우에만 무력화된다. 실행 사실을 산출물과 분리해 남긴다.
--
-- quote_base_date PK = 같은 기준일 재실행 시 멱등(UPSERT 로 갱신).

CREATE TABLE IF NOT EXISTS screening_runs (
    quote_base_date      DATE      PRIMARY KEY,          -- 산출 근거가 된 종가일(추천일과 동일 기준)
    recommendation_count INTEGER   NOT NULL,             -- 그 실행이 만든 추천 종목 수(0 = 통과 종목 없음)
    screened_at          TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_screening_runs_count_non_negative CHECK (recommendation_count >= 0)
);

-- 기존 추천 세트를 실행 기록으로 백필한다 — 이 마이그레이션 직후 첫 크론이
-- "기록 없음"으로 보고 이미 처리한 기준일을 재스크리닝하는 일을 막는다.
INSERT INTO screening_runs (quote_base_date, recommendation_count)
SELECT recommended_date, COUNT(*)
  FROM stock_recommendations
 GROUP BY recommended_date
ON CONFLICT (quote_base_date) DO NOTHING;
