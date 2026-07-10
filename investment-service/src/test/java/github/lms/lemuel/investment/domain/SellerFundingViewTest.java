package github.lms.lemuel.investment.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SellerFundingViewTest {

    @Test
    void confirmed_팩토리는_CONFIRMED_상태로_생성한다() {
        SellerFundingView v = SellerFundingView.confirmed(9001L, 777L, new BigDecimal("43425"));
        assertThat(v.getSettlementId()).isEqualTo(9001L);
        assertThat(v.getSellerId()).isEqualTo(777L);
        assertThat(v.getAmount()).isEqualByComparingTo("43425");
        assertThat(v.getStatus()).isEqualTo(FundingViewStatus.CONFIRMED);
    }

    @Test
    void 필수값_검증() {
        assertThatThrownBy(() -> SellerFundingView.confirmed(null, 1L, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SellerFundingView.confirmed(1L, null, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SellerFundingView.confirmed(1L, 1L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SellerFundingView.confirmed(1L, 1L, new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 금액_0은_허용된다() {
        assertThat(SellerFundingView.confirmed(1L, 1L, BigDecimal.ZERO).getAmount())
                .isEqualByComparingTo("0");
    }

    @Test
    void reconstitute로_재구성한다() {
        SellerFundingView v = SellerFundingView.reconstitute(1L, 2L, BigDecimal.TEN, FundingViewStatus.CONFIRMED);
        assertThat(v.getSettlementId()).isEqualTo(1L);
        assertThat(v.getSellerId()).isEqualTo(2L);
        assertThat(v.getStatus()).isEqualTo(FundingViewStatus.CONFIRMED);
    }
}
