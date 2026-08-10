package github.lms.lemuel.deposit.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.*;

/**
 * DepositOffsetShortfall 단위 테스트.
 *
 * <p>부족분은 예외로 소실되지 않고 1급 레코드로 남아야 한다 —
 * 그래야 재상계 배치가 회수를 시도할 수 있다.
 */
class DepositOffsetShortfallTest {

    private static final Long SELLER_ID = 42L;
    private static final String REF = "AUTH-100";

    private DepositOffsetShortfall openShortfall(String requested, String applied) {
        return DepositOffsetShortfall.open(SELLER_ID, DepositHolderType.CARD_AUTHORIZATION, REF,
                new BigDecimal(requested), new BigDecimal(applied), null, OffsetDateTime.now());
    }

    @Test
    @DisplayName("open 은 shortfall = requested - applied 로 OPEN 레코드를 만든다")
    void open_computesShortfall() {
        DepositOffsetShortfall shortfall = openShortfall("50000", "30000");

        assertThat(shortfall.getId()).isNull();
        assertThat(shortfall.getSellerId()).isEqualTo(SELLER_ID);
        assertThat(shortfall.getHolderType()).isEqualTo(DepositHolderType.CARD_AUTHORIZATION);
        assertThat(shortfall.getHolderReference()).isEqualTo(REF);
        assertThat(shortfall.getRequestedAmount()).isEqualByComparingTo("50000");
        assertThat(shortfall.getAppliedAmount()).isEqualByComparingTo("30000");
        assertThat(shortfall.getShortfallAmount()).isEqualByComparingTo("20000");
        assertThat(shortfall.getStatus()).isEqualTo(DepositShortfallStatus.OPEN);
        assertThat(shortfall.getSourceHoldId()).isNull();
        assertThat(shortfall.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("hold 기반 상계의 부족분은 sourceHoldId 를 보존한다")
    void open_keepsSourceHoldId() {
        DepositOffsetShortfall shortfall = DepositOffsetShortfall.open(SELLER_ID,
                DepositHolderType.CARD_AUTHORIZATION, REF,
                new BigDecimal("50000"), new BigDecimal("10000"), 88L, OffsetDateTime.now());

        assertThat(shortfall.getSourceHoldId()).isEqualTo(88L);
    }

    @Test
    @DisplayName("resolve 는 applied 를 늘리고 shortfall 을 줄이며 RESOLVED 로 전이한다")
    void resolve_transitions() {
        DepositOffsetShortfall shortfall = openShortfall("50000", "30000");

        shortfall.resolve(new BigDecimal("20000"));

        assertThat(shortfall.getAppliedAmount()).isEqualByComparingTo("50000");
        assertThat(shortfall.getShortfallAmount()).isEqualByComparingTo("0");
        assertThat(shortfall.getStatus()).isEqualTo(DepositShortfallStatus.RESOLVED);
    }

    @Test
    @DisplayName("이미 RESOLVED 된 건은 다시 resolve 할 수 없다")
    void resolve_rejectsNonOpen() {
        DepositOffsetShortfall shortfall = openShortfall("50000", "30000");
        shortfall.resolve(new BigDecimal("20000"));

        assertThatThrownBy(() -> shortfall.resolve(BigDecimal.ONE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("writeOff 는 OPEN 건만 WRITTEN_OFF 로 상각한다")
    void writeOff_transitions() {
        DepositOffsetShortfall shortfall = openShortfall("50000", "0");

        shortfall.writeOff();

        assertThat(shortfall.getStatus()).isEqualTo(DepositShortfallStatus.WRITTEN_OFF);
        assertThatThrownBy(shortfall::writeOff).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("필수 필드가 없으면 생성이 거부된다")
    void requiredFields() {
        assertThatThrownBy(() -> DepositOffsetShortfall.open(null,
                DepositHolderType.MANUAL, REF, BigDecimal.ONE, BigDecimal.ZERO, null, OffsetDateTime.now()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DepositOffsetShortfall.open(SELLER_ID,
                DepositHolderType.MANUAL, REF, BigDecimal.ONE, BigDecimal.ZERO, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rehydrate 는 영속 상태를 그대로 복원한다")
    void rehydrate_restoresState() {
        OffsetDateTime occurred = OffsetDateTime.now().minusHours(2);

        DepositOffsetShortfall shortfall = DepositOffsetShortfall.rehydrate(6L, SELLER_ID,
                DepositHolderType.LOAN_DISBURSEMENT, "LOAN-2",
                new BigDecimal("10000"), new BigDecimal("2000"), new BigDecimal("8000"),
                DepositShortfallStatus.OPEN, 5L, occurred);

        assertThat(shortfall.getId()).isEqualTo(6L);
        assertThat(shortfall.getShortfallAmount()).isEqualByComparingTo("8000");
        assertThat(shortfall.getStatus()).isEqualTo(DepositShortfallStatus.OPEN);
        assertThat(shortfall.getSourceHoldId()).isEqualTo(5L);
        assertThat(shortfall.getOccurredAt()).isEqualTo(occurred);
    }

    @Test
    @DisplayName("assignId 는 최초 1회만 허용된다")
    void assignId_onlyOnce() {
        DepositOffsetShortfall shortfall = openShortfall("100", "0");
        shortfall.assignId(1L);

        assertThat(shortfall.getId()).isEqualTo(1L);
        assertThatThrownBy(() -> shortfall.assignId(2L)).isInstanceOf(IllegalStateException.class);
    }
}
