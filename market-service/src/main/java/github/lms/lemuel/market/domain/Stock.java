package github.lms.lemuel.market.domain;

import java.time.Instant;

/**
 * 종목 카탈로그 항목 — 6자리 단축코드가 식별자(financial/company 와 공용 비즈니스 키).
 *
 * <p>지표 카탈로그를 미리 씨딩하는 economics 와 달리 종목 마스터는 시세 피드에서 파생된다 —
 * KRX 수집이 그날 피드에 등장한 종목의 이름/시장을 upsert 한다(상장/상장폐지 자동 반영).
 */
public record Stock(String stockCode, String isin, String name, Market market, Instant updatedAt) {

    public Stock {
        requireText(stockCode, "stockCode");
        requireText(name, "name");
        if (market == null) {
            throw new IllegalArgumentException("market 은(는) 필수입니다");
        }
    }

    private static void requireText(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(field + " 은(는) 필수입니다");
        }
    }
}
