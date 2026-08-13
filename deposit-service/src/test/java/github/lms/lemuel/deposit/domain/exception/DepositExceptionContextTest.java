package github.lms.lemuel.deposit.domain.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 도메인 예외가 <b>맥락을 담는다</b>는 계약 고정.
 *
 * <p>메시지 문자열만 던지면 핸들러·로그가 파싱에 의존하게 된다. 어떤 연산에서 어떤 값 때문에
 * 거부됐는지를 필드로 노출하는 것이 이 예외들의 존재 이유이므로, 그 접근자를 계약으로 못 박는다.
 *
 * <p>상속 관계도 함께 검증한다 — 이 예외들은 공용 Kafka 에러 핸들러의 "즉시 DLT" 분류
 * (IllegalArgumentException / IllegalStateException)에 걸려야 한다. RuntimeException 으로
 * 갈아타면 비재시도 사건이 3회 재시도를 돌게 되므로, 상속 대상이 곧 재시도 정책이다.
 */
class DepositExceptionContextTest {

    @Nested
    @DisplayName("InvalidDepositAmountException")
    class Amount {

        @Test
        @DisplayName("연산명과 금액을 담고, 즉시-DLT 분류(IAE 계열)를 유지한다")
        void carriesContextAndClassification() {
            BigDecimal amount = new BigDecimal("-1.00");

            InvalidDepositAmountException e =
                    new InvalidDepositAmountException("금액은 양수여야 합니다", "place", amount);

            assertThat(e.getOperation()).isEqualTo("place");
            assertThat(e.getAmount()).isEqualByComparingTo(amount);
            assertThat(e).hasMessage("금액은 양수여야 합니다")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("금액이 null 인 경우(정규화 이전 유입)도 그대로 담는다")
        void allowsNullAmount() {
            InvalidDepositAmountException e =
                    new InvalidDepositAmountException("금액은 null 일 수 없습니다", "normalize", null);

            assertThat(e.getAmount()).isNull();
            assertThat(e.getOperation()).isEqualTo("normalize");
        }
    }

    @Nested
    @DisplayName("InvalidDepositStateException")
    class State {

        @Test
        @DisplayName("현재 상태와 시도한 전이를 담고, 즉시-DLT 분류(ISE 계열)를 유지한다")
        void carriesContextAndClassification() {
            InvalidDepositStateException e =
                    new InvalidDepositStateException("ACTIVE 상태만 만료 가능합니다", "EXPIRED", "expire");

            assertThat(e.getCurrentState()).isEqualTo("EXPIRED");
            assertThat(e.getAttemptedTransition()).isEqualTo("expire");
            assertThat(e).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("DepositInvariantViolationException")
    class Invariant {

        @Test
        @DisplayName("불변식 붕괴는 입력 오류가 아니라 상태 오류로 분류된다")
        void isStateNotArgument() {
            DepositInvariantViolationException e =
                    new DepositInvariantViolationException("불변식 위반: available < 0 (-1)");

            assertThat(e).hasMessage("불변식 위반: available < 0 (-1)")
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("InsufficientDepositException")
    class Insufficient {

        @Test
        @DisplayName("셀러·연산을 담는다 — 잔고 부족은 비즈니스 정상 결과라 별도 분류다")
        void carriesSellerAndOperation() {
            InsufficientDepositException e =
                    new InsufficientDepositException("available(0) < debit(100)", "S1", "debit");

            assertThat(e.getSellerId()).isEqualTo("S1");
            assertThat(e.getOperation()).isEqualTo("debit");
            assertThat(e).isInstanceOf(RuntimeException.class)
                    .isNotInstanceOf(IllegalArgumentException.class)
                    .isNotInstanceOf(IllegalStateException.class);
        }
    }
}
