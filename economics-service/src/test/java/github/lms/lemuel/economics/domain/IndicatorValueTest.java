package github.lms.lemuel.economics.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

class IndicatorValueTest {

    private IndicatorValue value(String v, LocalDate d) {
        return new IndicatorValue(null, "BASE_RATE", d, new BigDecimal(v), ValueSource.SEED, null);
    }

    private IndicatorValue value(String code, String v, LocalDate d) {
        return new IndicatorValue(null, code, d, new BigDecimal(v), ValueSource.SEED, null);
    }

    @Test
    void 전기_대비_변동폭과_변동률을_계산한다() {
        IndicatorValue prev = value("3.00", LocalDate.of(2026, 5, 1));
        IndicatorValue curr = value("3.25", LocalDate.of(2026, 6, 1));

        IndicatorValue.Change change = curr.changeFrom(prev);

        assertThat(change.amount()).isEqualByComparingTo("0.25");
        assertThat(change.ratePercent()).isEqualByComparingTo("8.3333"); // 0.25/3.00*100, scale 4 HALF_UP
    }

    @Test
    void 이전값이_null_이면_변동은_null() {
        assertThat(value("3.25", LocalDate.of(2026, 6, 1)).changeFrom(null)).isNull();
    }

    @Test
    void 이전값이_0_이면_변동률은_null_변동폭은_계산() {
        IndicatorValue.Change change = value("1.00", LocalDate.of(2026, 6, 1))
                .changeFrom(value("0.00", LocalDate.of(2026, 5, 1)));
        assertThat(change.amount()).isEqualByComparingTo("1.00");
        assertThat(change.ratePercent()).isNull();
    }

    @Test
    void 값이_null_이면_생성_거부() {
        assertThatThrownBy(() -> new IndicatorValue(null, "BASE_RATE",
                LocalDate.of(2026, 6, 1), null, ValueSource.ECOS, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 서로_다른_지표의_관측치끼리_변동_계산은_거부() {
        IndicatorValue base = value("BASE_RATE", "3.25", LocalDate.of(2026, 6, 1));
        IndicatorValue usd = value("USD_KRW", "1380.00", LocalDate.of(2026, 5, 1));

        assertThatThrownBy(() -> base.changeFrom(usd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 변동률은_scale4_HALF_UP_경계에서_올림된다() {
        // 0.12345 → 5번째 자리(5)에서 HALF_UP → 0.1235
        IndicatorValue prev = value("100", LocalDate.of(2026, 5, 1));
        IndicatorValue curr = value("100.12345", LocalDate.of(2026, 6, 1));

        IndicatorValue.Change change = curr.changeFrom(prev);

        assertThat(change.ratePercent()).isEqualByComparingTo("0.1235");
    }
}
