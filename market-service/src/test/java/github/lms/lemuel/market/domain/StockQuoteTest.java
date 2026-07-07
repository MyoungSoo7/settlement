package github.lms.lemuel.market.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockQuoteTest {

    @Test
    void 필수값이_모두_있으면_생성된다() {
        StockQuote quote = new StockQuote(null, "005930", LocalDate.of(2026, 7, 7),
                new BigDecimal("78000.00"), null, null, null, null, null,
                null, null, null, null, ValueSource.KRX, null);

        assertThat(quote.stockCode()).isEqualTo("005930");
        assertThat(quote.closePrice()).isEqualByComparingTo("78000.00");
        assertThat(quote.source()).isEqualTo(ValueSource.KRX);
    }

    @Test
    void stockCode_가_없으면_예외() {
        assertThatThrownBy(() -> new StockQuote(null, " ", LocalDate.now(),
                BigDecimal.ONE, null, null, null, null, null,
                null, null, null, null, ValueSource.SEED, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stockCode");
    }

    @Test
    void baseDate_가_없으면_예외() {
        assertThatThrownBy(() -> new StockQuote(null, "005930", null,
                BigDecimal.ONE, null, null, null, null, null,
                null, null, null, null, ValueSource.SEED, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseDate");
    }

    @Test
    void closePrice_가_없으면_예외() {
        assertThatThrownBy(() -> new StockQuote(null, "005930", LocalDate.now(),
                null, null, null, null, null, null,
                null, null, null, null, ValueSource.SEED, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("closePrice");
    }

    @Test
    void source_가_없으면_예외() {
        assertThatThrownBy(() -> new StockQuote(null, "005930", LocalDate.now(),
                BigDecimal.ONE, null, null, null, null, null,
                null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
    }
}
