package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 금액 반올림 정책 값 객체 회귀 가드. 원화 기본값·절사 동작·"정책 단위로 정확히 표현 가능한가" 판정
 * (계약 원금 자동 보정 차단의 근거) + 생성 불변식을 확인한다.
 */
class RoundingPolicyTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Nested
    class 원화기본정책 {
        @Test void 원화는_소수점0자리_HALF_UP() {
            assertThat(RoundingPolicy.KRW.scale()).isZero();
            assertThat(RoundingPolicy.KRW.mode()).isEqualTo(RoundingMode.HALF_UP);
        }

        @Test void 절사는_원단위_HALF_UP이다() {
            assertThat(RoundingPolicy.KRW.round(bd("4166.5"))).isEqualByComparingTo("4167");
            assertThat(RoundingPolicy.KRW.round(bd("4166.4"))).isEqualByComparingTo("4166");
        }

        @Test void 단위설명은_원단위정수() {
            assertThat(RoundingPolicy.KRW.unitDescription()).isEqualTo("원 단위 정수");
        }
    }

    @Nested
    class 소수점정책 {
        private final RoundingPolicy usd = new RoundingPolicy(2, RoundingMode.HALF_UP);

        @Test void 소수점2자리로_절사한다() {
            assertThat(usd.round(bd("4166.666"))).isEqualByComparingTo("4166.67");
            assertThat(usd.round(bd("4166.666")).scale()).isEqualTo(2);
        }

        @Test void 단위설명은_자릿수를_담는다() {
            assertThat(usd.unitDescription()).isEqualTo("소수점 2자리");
        }

        @Test void 내림정책도_구성할_수_있다() {
            RoundingPolicy floor = new RoundingPolicy(0, RoundingMode.FLOOR);
            assertThat(floor.round(bd("4166.9"))).isEqualByComparingTo("4166");
        }
    }

    @Nested
    class 정확표현판정 {
        @Test void 정수는_원화정책으로_정확히_표현된다() {
            assertThat(RoundingPolicy.KRW.isExact(bd("1000000"))).isTrue();
        }

        @Test void 값이_정수면_소수표기라도_정확하다() {
            assertThat(RoundingPolicy.KRW.isExact(bd("1000000.00"))).isTrue();
        }

        @Test void 원단위_미만_소수는_정확하지_않다() {
            assertThat(RoundingPolicy.KRW.isExact(bd("1000000.5"))).isFalse();
        }

        @Test void 영은_정확하다() {
            assertThat(RoundingPolicy.KRW.isExact(BigDecimal.ZERO)).isTrue();
        }

        @Test void null은_정확하지_않다() {
            assertThat(RoundingPolicy.KRW.isExact(null)).isFalse();
        }

        @Test void 소수점2자리_정책은_센트단위까지_허용한다() {
            RoundingPolicy usd = new RoundingPolicy(2, RoundingMode.HALF_UP);
            assertThat(usd.isExact(bd("10.25"))).isTrue();
            assertThat(usd.isExact(bd("10.255"))).isFalse();
        }
    }

    @Nested
    class 생성불변식 {
        @Test void 자릿수가_음수면_예외() {
            assertThatThrownBy(() -> new RoundingPolicy(-1, RoundingMode.HALF_UP))
                    .isInstanceOf(LoanInvariantViolationException.class);
        }

        @Test void 반올림방식이_null이면_예외() {
            assertThatThrownBy(() -> new RoundingPolicy(0, null))
                    .isInstanceOf(LoanInvariantViolationException.class);
        }

        @Test void 절사대상이_null이면_예외() {
            assertThatThrownBy(() -> RoundingPolicy.KRW.round(null))
                    .isInstanceOf(LoanInvariantViolationException.class);
        }
    }
}
