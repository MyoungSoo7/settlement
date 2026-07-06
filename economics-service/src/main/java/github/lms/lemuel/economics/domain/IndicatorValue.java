package github.lms.lemuel.economics.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/** 지표 관측치 1건. 파생값(전기 대비 변동)은 저장하지 않고 여기서 계산한다. */
public record IndicatorValue(Long id, String indicatorCode, LocalDate observedDate,
                             BigDecimal value, ValueSource source, Instant syncedAt) {

    public IndicatorValue {
        if (indicatorCode == null || indicatorCode.isBlank()) {
            throw new IllegalArgumentException("indicatorCode 은(는) 필수입니다");
        }
        if (observedDate == null) {
            throw new IllegalArgumentException("observedDate 은(는) 필수입니다");
        }
        if (value == null) {
            throw new IllegalArgumentException("value 은(는) 필수입니다");
        }
        if (source == null) {
            throw new IllegalArgumentException("source 은(는) 필수입니다");
        }
    }

    /** 변동폭(amount)과 변동률 %(ratePercent, scale 4 HALF_UP). 분모 0 이면 변동률만 null. */
    public record Change(BigDecimal amount, BigDecimal ratePercent) { }

    public Change changeFrom(IndicatorValue previous) {
        if (previous == null) {
            return null;
        }
        // 서로 다른 지표의 관측치끼리 변동을 계산하는 실수를 원천 차단.
        if (!indicatorCode.equals(previous.indicatorCode)) {
            throw new IllegalArgumentException(
                    "서로 다른 지표의 관측치끼리는 변동을 계산할 수 없습니다: "
                            + indicatorCode + " vs " + previous.indicatorCode);
        }
        BigDecimal amount = value.subtract(previous.value);
        BigDecimal ratePercent = previous.value.signum() == 0
                ? null
                : amount.multiply(BigDecimal.valueOf(100))
                        .divide(previous.value, 4, RoundingMode.HALF_UP);
        return new Change(amount, ratePercent);
    }
}
