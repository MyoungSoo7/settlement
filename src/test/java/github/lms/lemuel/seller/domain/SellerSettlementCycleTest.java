package github.lms.lemuel.seller.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

class SellerSettlementCycleTest {

    @Nested
    @DisplayName("DAILY 정산 주기")
    class Daily {
        @Test
        void 매일_정산_대상이다() {
            Seller seller = createSeller(SettlementCycle.DAILY, null, null);
            assertThat(seller.isSettlementDueOn(LocalDate.of(2026, 4, 25))).isTrue();
            assertThat(seller.isSettlementDueOn(LocalDate.of(2026, 4, 26))).isTrue();
        }
    }

    @Nested
    @DisplayName("WEEKLY 정산 주기")
    class Weekly {
        @Test
        void 지정된_요일에만_정산_대상이다() {
            Seller seller = createSeller(SettlementCycle.WEEKLY, DayOfWeek.MONDAY, null);
            // 2026-04-27 = MONDAY
            assertThat(seller.isSettlementDueOn(LocalDate.of(2026, 4, 27))).isTrue();
            // 2026-04-25 = FRIDAY
            assertThat(seller.isSettlementDueOn(LocalDate.of(2026, 4, 25))).isFalse();
        }
    }

    @Nested
    @DisplayName("MONTHLY 정산 주기")
    class Monthly {
        @Test
        void 지정된_날짜에만_정산_대상이다() {
            Seller seller = createSeller(SettlementCycle.MONTHLY, null, 15);
            assertThat(seller.isSettlementDueOn(LocalDate.of(2026, 4, 15))).isTrue();
            assertThat(seller.isSettlementDueOn(LocalDate.of(2026, 4, 16))).isFalse();
        }

        @Test
        void 유효하지_않은_날짜는_예외를_던진다() {
            assertThatThrownBy(() -> createSeller(SettlementCycle.MONTHLY, null, 29))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 유효하지_않은_날짜_0도_예외를_던진다() {
            assertThatThrownBy(() -> createSeller(SettlementCycle.MONTHLY, null, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private Seller createSeller(SettlementCycle cycle, DayOfWeek weeklyDay, Integer monthlyDay) {
        Seller seller = Seller.create(1L, "테스트상점", "1234567890", "홍길동", "010-1234-5678", "test@test.com");
        seller.updateSettlementCycle(cycle, weeklyDay, monthlyDay);
        return seller;
    }
}
