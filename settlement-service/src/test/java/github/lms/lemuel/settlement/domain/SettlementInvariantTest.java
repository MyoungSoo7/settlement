package github.lms.lemuel.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based 유사 테스트 (외부 라이브러리 없이 난수 생성으로 불변식 검증).
 *
 * 불변식: 임의 결제 금액 P, 0< 누적환불 R ≤ P 에 대해
 *   netAmount = P - R - commission
 *   commission = round(P * 0.03, HALF_UP)
 * 를 항상 만족한다.
 */
class SettlementInvariantTest {

    @Test @DisplayName("[property] 임의 결제금액 + 임의 환불 시퀀스에서 netAmount 불변식 유지")
    void netAmountInvariantHoldsForRandomSequences() {
        Random rnd = new Random(20260422L); // deterministic seed

        for (int iter = 0; iter < 200; iter++) {
            long amount = 1000L + rnd.nextInt(9_999_000); // 1,000 ~ 10,000,000
            BigDecimal payment = new BigDecimal(amount);
            Settlement s = Settlement.createFromPayment(
                    (long) iter + 1, (long) iter + 100, payment, LocalDate.now());

            BigDecimal expectedCommission = payment.multiply(new BigDecimal("0.03"))
                    .setScale(2, RoundingMode.HALF_UP);
            assertThat(s.getCommission()).isEqualByComparingTo(expectedCommission);
            assertThat(s.getNetAmount()).isEqualByComparingTo(
                    payment.subtract(expectedCommission).setScale(2, RoundingMode.HALF_UP));

            // 누적 환불 분할 적용
            BigDecimal cumulativeRefund = BigDecimal.ZERO;
            int refundSteps = rnd.nextInt(5);
            for (int k = 0; k < refundSteps; k++) {
                BigDecimal remaining = payment.subtract(cumulativeRefund);
                if (remaining.compareTo(BigDecimal.ONE) <= 0) break;
                long step = 1 + (long) (rnd.nextDouble() * remaining.longValueExact());
                BigDecimal refund = new BigDecimal(step);
                s.adjustForRefund(refund);
                cumulativeRefund = cumulativeRefund.add(refund);

                // 불변식: refundedAmount = 누적 환불
                assertThat(s.getRefundedAmount()).isEqualByComparingTo(cumulativeRefund);
                // 불변식: netAmount = payment - refunded - commission
                BigDecimal expectedNet = payment.subtract(cumulativeRefund).subtract(expectedCommission)
                        .setScale(2, RoundingMode.HALF_UP);
                assertThat(s.getNetAmount()).isEqualByComparingTo(expectedNet);
            }
        }
    }

    @Test @DisplayName("[invariant] 누적환불이 결제금액을 초과하려 하면 예외")
    void refundExceedsPayment_throws() {
        Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("10000"), LocalDate.now());
        s.adjustForRefund(new BigDecimal("6000"));

        assertThatThrownBy(() -> s.adjustForRefund(new BigDecimal("5000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");
    }

    @Test @DisplayName("[invariant] DONE 정산은 adjustForRefund 로 직접 수정 불가")
    void doneSettlement_isImmutable() {
        Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("10000"), LocalDate.now());
        s.startProcessing();
        s.complete();
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.DONE);

        assertThatThrownBy(() -> s.adjustForRefund(new BigDecimal("1000")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("immutable");
    }

    @Test @DisplayName("[invariant] 0 이하 환불은 예외")
    void nonPositiveRefund_throws() {
        Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("10000"), LocalDate.now());

        assertThatThrownBy(() -> s.adjustForRefund(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.adjustForRefund(new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
