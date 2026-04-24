package github.lms.lemuel.ledger.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class MoneyTest {

    @Nested
    @DisplayName("생성")
    class Creation {
        @Test
        void 금액과_통화로_생성한다() {
            Money money = Money.of(new BigDecimal("10000"), Currency.KRW);
            assertThat(money.amount()).isEqualByComparingTo("10000.00");
            assertThat(money.currency()).isEqualTo(Currency.KRW);
        }

        @Test
        void KRW_기본_통화로_생성한다() {
            Money money = Money.krw(new BigDecimal("5000"));
            assertThat(money.currency()).isEqualTo(Currency.KRW);
            assertThat(money.amount()).isEqualByComparingTo("5000.00");
        }

        @Test
        void ZERO_상수는_0원이다() {
            assertThat(Money.ZERO.amount()).isEqualByComparingTo("0.00");
            assertThat(Money.ZERO.currency()).isEqualTo(Currency.KRW);
        }

        @Test
        void null_금액은_예외를_던진다() {
            assertThatThrownBy(() -> Money.of(null, Currency.KRW))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 소수점_셋째자리는_반올림된다() {
            Money money = Money.krw(new BigDecimal("100.005"));
            assertThat(money.amount()).isEqualByComparingTo("100.01");
        }
    }

    @Nested
    @DisplayName("산술연산")
    class Arithmetic {
        @Test
        void 더하기() {
            Money a = Money.krw(new BigDecimal("10000"));
            Money b = Money.krw(new BigDecimal("5000"));
            assertThat(a.add(b).amount()).isEqualByComparingTo("15000.00");
        }

        @Test
        void 빼기() {
            Money a = Money.krw(new BigDecimal("10000"));
            Money b = Money.krw(new BigDecimal("3000"));
            assertThat(a.subtract(b).amount()).isEqualByComparingTo("7000.00");
        }

        @Test
        void 곱하기_비율() {
            Money money = Money.krw(new BigDecimal("10000"));
            Money result = money.multiply(new BigDecimal("0.03"));
            assertThat(result.amount()).isEqualByComparingTo("300.00");
        }

        @Test
        void 다른_통화_연산은_예외를_던진다() {
            Money krw = Money.krw(new BigDecimal("10000"));
            Money usd = Money.of(new BigDecimal("100"), Currency.USD);
            assertThatThrownBy(() -> krw.add(usd))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("currency");
        }
    }

    @Nested
    @DisplayName("비교")
    class Comparison {
        @Test
        void isGreaterThan() {
            assertThat(Money.krw(new BigDecimal("10000")).isGreaterThan(Money.krw(new BigDecimal("5000")))).isTrue();
            assertThat(Money.krw(new BigDecimal("5000")).isGreaterThan(Money.krw(new BigDecimal("10000")))).isFalse();
        }

        @Test
        void isPositive() {
            assertThat(Money.krw(new BigDecimal("100")).isPositive()).isTrue();
            assertThat(Money.ZERO.isPositive()).isFalse();
            assertThat(Money.krw(new BigDecimal("-100")).isPositive()).isFalse();
        }

        @Test
        void equals_동일_금액_동일_통화() {
            Money a = Money.krw(new BigDecimal("10000.00"));
            Money b = Money.krw(new BigDecimal("10000"));
            assertThat(a).isEqualTo(b);
        }
    }
}
