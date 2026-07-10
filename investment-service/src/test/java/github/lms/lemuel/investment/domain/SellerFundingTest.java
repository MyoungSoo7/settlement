package github.lms.lemuel.investment.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SellerFundingTest {

    @Test
    void available은_확정재원에서_집행합을_뺀다() {
        SellerFunding f = SellerFunding.of(7L, new BigDecimal("1000000"), new BigDecimal("300000"));
        assertThat(f.sellerId()).isEqualTo(7L);
        assertThat(f.confirmedTotal()).isEqualByComparingTo("1000000");
        assertThat(f.investedTotal()).isEqualByComparingTo("300000");
        assertThat(f.available()).isEqualByComparingTo("700000");
    }

    @Test
    void null_합계는_0으로_대체된다() {
        SellerFunding f = SellerFunding.of(7L, null, null);
        assertThat(f.confirmedTotal()).isEqualByComparingTo("0");
        assertThat(f.investedTotal()).isEqualByComparingTo("0");
        assertThat(f.available()).isEqualByComparingTo("0");
    }
}
