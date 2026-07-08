package github.lms.lemuel.economics.domain;

import java.time.Instant;

/** 경제지표 카탈로그 항목 — 지표 추가는 스키마 변경 없이 indicators row 추가로 끝난다. */
public record Indicator(String code, String name, String unit, IndicatorCycle cycle,
                        String ecosStatCode, String ecosItemCode, Instant updatedAt) {

    public Indicator {
        requireText(code, "code");
        requireText(name, "name");
        requireText(unit, "unit");
        requireText(ecosStatCode, "ecosStatCode");
        requireText(ecosItemCode, "ecosItemCode");
        if (cycle == null) {
            throw new IllegalArgumentException("cycle 은(는) 필수입니다");
        }
    }

    private static void requireText(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(field + " 은(는) 필수입니다");
        }
    }
}
