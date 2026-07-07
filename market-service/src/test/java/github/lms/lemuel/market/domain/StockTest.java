package github.lms.lemuel.market.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockTest {

    @Test
    void 필수값이_있으면_생성된다() {
        Stock stock = new Stock("005930", "KR7005930003", "삼성전자", Market.KOSPI, null);

        assertThat(stock.stockCode()).isEqualTo("005930");
        assertThat(stock.market()).isEqualTo(Market.KOSPI);
    }

    @Test
    void stockCode_가_없으면_예외() {
        assertThatThrownBy(() -> new Stock(" ", null, "삼성전자", Market.KOSPI, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stockCode");
    }

    @Test
    void name_이_없으면_예외() {
        assertThatThrownBy(() -> new Stock("005930", null, "", Market.KOSPI, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void market_이_없으면_예외() {
        assertThatThrownBy(() -> new Stock("005930", null, "삼성전자", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("market");
    }
}
