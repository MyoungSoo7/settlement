package github.lms.lemuel.investment.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestmentOrderTest {

    private static InvestmentOrder requested() {
        return InvestmentOrder.request(7L, "005930", new BigDecimal("1000000"), 82, "AA");
    }

    @Test
    void 신규주문은_REQUESTED이고_필드를_보존한다() {
        InvestmentOrder o = requested();
        assertThat(o.getStatus()).isEqualTo(InvestmentOrderStatus.REQUESTED);
        assertThat(o.getSellerId()).isEqualTo(7L);
        assertThat(o.getStockCode()).isEqualTo("005930");
        assertThat(o.getAmount()).isEqualByComparingTo("1000000");
        assertThat(o.getScoreAtOrder()).isEqualTo(82);
        assertThat(o.getGradeAtOrder()).isEqualTo("AA");
        assertThat(o.getCreatedAt()).isNotNull();
        assertThat(o.getId()).isNull();
    }

    @Test
    void 신청_검증_실패() {
        assertThatThrownBy(() -> InvestmentOrder.request(null, "005930", BigDecimal.TEN, 1, "A"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvestmentOrder.request(7L, "12345", BigDecimal.TEN, 1, "A"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvestmentOrder.request(7L, "abcdef", BigDecimal.TEN, 1, "A"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvestmentOrder.request(7L, "005930", BigDecimal.ZERO, 1, "A"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvestmentOrder.request(7L, "005930", new BigDecimal("-1"), 1, "A"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvestmentOrder.request(7L, "005930", null, 1, "A"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 정상_전이_REQUESTED_APPROVED_EXECUTED() {
        InvestmentOrder o = requested();
        o.approve();
        assertThat(o.getStatus()).isEqualTo(InvestmentOrderStatus.APPROVED);
        o.execute();
        assertThat(o.getStatus()).isEqualTo(InvestmentOrderStatus.EXECUTED);
    }

    @Test
    void REQUESTED에서_거절과_취소() {
        InvestmentOrder rejected = requested();
        rejected.reject();
        assertThat(rejected.getStatus()).isEqualTo(InvestmentOrderStatus.REJECTED);

        InvestmentOrder canceled = requested();
        canceled.cancel();
        assertThat(canceled.getStatus()).isEqualTo(InvestmentOrderStatus.CANCELED);
    }

    @Test
    void APPROVED에서도_취소가능() {
        InvestmentOrder o = requested();
        o.approve();
        o.cancel();
        assertThat(o.getStatus()).isEqualTo(InvestmentOrderStatus.CANCELED);
    }

    @Test
    void 비정상_전이는_IllegalState() {
        assertThatThrownBy(() -> requested().execute()).isInstanceOf(IllegalStateException.class);

        InvestmentOrder approvedTwice = requested();
        approvedTwice.approve();
        assertThatThrownBy(approvedTwice::approve).isInstanceOf(IllegalStateException.class);

        InvestmentOrder executed = requested();
        executed.approve();
        executed.execute();
        assertThatThrownBy(executed::cancel).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(executed::reject).isInstanceOf(IllegalStateException.class);

        InvestmentOrder approved = requested();
        approved.approve();
        assertThatThrownBy(approved::reject).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reconstitute로_영속상태를_재구성한다() {
        LocalDateTime now = LocalDateTime.now();
        InvestmentOrder o = InvestmentOrder.reconstitute(9L, 7L, "005930", new BigDecimal("500"),
                75, "A", InvestmentOrderStatus.EXECUTED, now);
        assertThat(o.getId()).isEqualTo(9L);
        assertThat(o.getStatus()).isEqualTo(InvestmentOrderStatus.EXECUTED);
        assertThat(o.getCreatedAt()).isEqualTo(now);
    }
}
