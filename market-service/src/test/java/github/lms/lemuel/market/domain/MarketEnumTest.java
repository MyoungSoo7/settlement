package github.lms.lemuel.market.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketEnumTest {

    @Test
    void 정상_시장코드는_매핑된다() {
        assertThat(Market.fromCode("KOSPI")).isEqualTo(Market.KOSPI);
        assertThat(Market.fromCode(" kosdaq ")).isEqualTo(Market.KOSDAQ);
        assertThat(Market.fromCode("KONEX")).isEqualTo(Market.KONEX);
    }

    @Test
    void 알수없는_값이나_공백은_null() {
        assertThat(Market.fromCode(null)).isNull();
        assertThat(Market.fromCode("")).isNull();
        assertThat(Market.fromCode("NASDAQ")).isNull();
    }
}
