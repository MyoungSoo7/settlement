package github.lms.lemuel.deposit.domain;

import github.lms.lemuel.deposit.domain.exception.DepositInvariantViolationException;
import github.lms.lemuel.deposit.domain.exception.InvalidDepositAmountException;
import github.lms.lemuel.deposit.domain.exception.InvalidDepositStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

/**
 * DepositHold 상태머신 단위 테스트.
 *
 * <p>검증 대상:
 * <ul>
 *   <li>ACTIVE → PARTIALLY_CAPTURED → CAPTURED 전이와 remaining 감소
 *   <li>expire / voidHold / release 의 허용 상태 게이트
 *   <li>캡처 금액이 remaining 을 초과하면 거부 (locked 초과 인출 방지)
 * </ul>
 */
class DepositHoldTest {

    private static final Long ACCOUNT_ID = 7L;
    private static final String REF = "AUTH-0001";

    private DepositHold activeHold(String amount) {
        return DepositHold.place(ACCOUNT_ID, DepositHolderType.CARD_AUTHORIZATION, REF,
                new BigDecimal(amount), LocalDateTime.now().plusDays(3));
    }

    @Test
    @DisplayName("place 시 ACTIVE 이고 remaining = original, 자연키가 보존된다")
    void place_initialState() {
        DepositHold hold = activeHold("50000");

        assertThat(hold.getStatus()).isEqualTo(DepositHoldStatus.ACTIVE);
        assertThat(hold.getOriginalAmount()).isEqualByComparingTo("50000");
        assertThat(hold.getRemainingAmount()).isEqualByComparingTo("50000");
        assertThat(hold.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(hold.getHolderType()).isEqualTo(DepositHolderType.CARD_AUTHORIZATION);
        assertThat(hold.getHolderReference()).isEqualTo(REF);
        assertThat(hold.getExpiresAt()).isNotNull();
        assertThat(hold.getCreatedAt()).isNotNull();
        assertThat(hold.getUpdatedAt()).isNotNull();
        assertThat(hold.getVersion()).isZero();
        assertThat(hold.getId()).isNull();
        assertThat(hold.isActive()).isTrue();
    }

    @Test
    @DisplayName("hold 금액이 0 이하이면 거부한다")
    void place_rejectsNonPositive() {
        assertThatThrownBy(() -> DepositHold.place(ACCOUNT_ID, DepositHolderType.MANUAL, REF,
                BigDecimal.ZERO, null))
                .isInstanceOf(InvalidDepositAmountException.class);
        assertThatThrownBy(() -> DepositHold.place(ACCOUNT_ID, DepositHolderType.MANUAL, REF,
                null, null))
                .isInstanceOf(InvalidDepositAmountException.class);
    }

    @Nested
    @DisplayName("capture — 부분/전액 캡처")
    class CaptureTests {

        @Test
        @DisplayName("부분 캡처 시 PARTIALLY_CAPTURED 이고 remaining 이 줄어든다")
        void capture_partial() {
            DepositHold hold = activeHold("50000");

            BigDecimal remaining = hold.capture(new BigDecimal("20000"));

            assertThat(remaining).isEqualByComparingTo("30000");
            assertThat(hold.getRemainingAmount()).isEqualByComparingTo("30000");
            assertThat(hold.getStatus()).isEqualTo(DepositHoldStatus.PARTIALLY_CAPTURED);
            assertThat(hold.isActive()).isTrue();
        }

        @Test
        @DisplayName("전액 캡처 시 CAPTURED 이고 remaining = 0, 더 이상 활성이 아니다")
        void capture_full() {
            DepositHold hold = activeHold("50000");

            BigDecimal remaining = hold.capture(new BigDecimal("50000"));

            assertThat(remaining).isEqualByComparingTo("0");
            assertThat(hold.getStatus()).isEqualTo(DepositHoldStatus.CAPTURED);
            assertThat(hold.isActive()).isFalse();
        }

        @Test
        @DisplayName("부분 캡처 후 이어서 잔여 전액을 캡처하면 CAPTURED 로 전이한다")
        void capture_partialThenFull() {
            DepositHold hold = activeHold("50000");

            hold.capture(new BigDecimal("20000"));
            hold.capture(new BigDecimal("30000"));

            assertThat(hold.getStatus()).isEqualTo(DepositHoldStatus.CAPTURED);
            assertThat(hold.getRemainingAmount()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("remaining 초과 캡처는 거부한다 — locked 초과 인출 방지")
        void capture_rejectsOverRemaining() {
            DepositHold hold = activeHold("50000");

            assertThatThrownBy(() -> hold.capture(new BigDecimal("50000.01")))
                    .isInstanceOf(InvalidDepositAmountException.class)
                    .hasMessageContaining("remaining");
        }

        @Test
        @DisplayName("캡처 금액이 0 이하이면 거부한다")
        void capture_rejectsNonPositive() {
            DepositHold hold = activeHold("50000");

            assertThatThrownBy(() -> hold.capture(BigDecimal.ZERO))
                    .isInstanceOf(InvalidDepositAmountException.class);
            assertThatThrownBy(() -> hold.capture(null))
                    .isInstanceOf(InvalidDepositAmountException.class);
        }

        @Test
        @DisplayName("CAPTURED 된 hold 는 다시 캡처할 수 없다")
        void capture_rejectsWhenTerminal() {
            DepositHold hold = activeHold("50000");
            hold.capture(new BigDecimal("50000"));

            assertThatThrownBy(() -> hold.capture(new BigDecimal("1")))
                    .isInstanceOf(InvalidDepositStateException.class);
        }
    }

    @Nested
    @DisplayName("expire / voidHold / release — 종료 전이")
    class TerminalTransitionTests {

        @Test
        @DisplayName("ACTIVE hold 만 만료할 수 있다")
        void expire_onlyFromActive() {
            DepositHold hold = activeHold("10000");
            hold.expire();

            assertThat(hold.getStatus()).isEqualTo(DepositHoldStatus.EXPIRED);
            assertThat(hold.isActive()).isFalse();
            assertThatThrownBy(hold::expire).isInstanceOf(InvalidDepositStateException.class);
        }

        @Test
        @DisplayName("부분 캡처된 hold 는 만료 대상이 아니다")
        void expire_rejectsPartiallyCaptured() {
            DepositHold hold = activeHold("10000");
            hold.capture(new BigDecimal("1000"));

            assertThatThrownBy(hold::expire).isInstanceOf(InvalidDepositStateException.class);
        }

        @Test
        @DisplayName("ACTIVE·PARTIALLY_CAPTURED hold 는 취소할 수 있다")
        void voidHold_fromActiveAndPartial() {
            DepositHold active = activeHold("10000");
            active.voidHold();
            assertThat(active.getStatus()).isEqualTo(DepositHoldStatus.VOIDED);

            DepositHold partial = activeHold("10000");
            partial.capture(new BigDecimal("1000"));
            partial.voidHold();
            assertThat(partial.getStatus()).isEqualTo(DepositHoldStatus.VOIDED);
        }

        @Test
        @DisplayName("EXPIRED hold 는 취소할 수 없다")
        void voidHold_rejectsTerminal() {
            DepositHold hold = activeHold("10000");
            hold.expire();

            assertThatThrownBy(hold::voidHold).isInstanceOf(InvalidDepositStateException.class);
        }

        @Test
        @DisplayName("ACTIVE·PARTIALLY_CAPTURED hold 는 잔여 해제할 수 있다")
        void release_fromActiveAndPartial() {
            DepositHold active = activeHold("10000");
            active.release();
            assertThat(active.getStatus()).isEqualTo(DepositHoldStatus.RELEASED);

            DepositHold partial = activeHold("10000");
            partial.capture(new BigDecimal("4000"));
            partial.release();
            assertThat(partial.getStatus()).isEqualTo(DepositHoldStatus.RELEASED);
        }

        @Test
        @DisplayName("VOIDED hold 는 해제할 수 없다")
        void release_rejectsTerminal() {
            DepositHold hold = activeHold("10000");
            hold.voidHold();

            assertThatThrownBy(hold::release).isInstanceOf(InvalidDepositStateException.class);
        }
    }

    @Nested
    @DisplayName("rehydrate / assignId — 영속 어댑터 경계")
    class PersistenceBoundaryTests {

        @Test
        @DisplayName("rehydrate 는 영속 상태를 그대로 복원한다")
        void rehydrate_restoresState() {
            LocalDateTime now = LocalDateTime.now();

            DepositHold hold = DepositHold.rehydrate(9L, ACCOUNT_ID,
                    DepositHolderType.LOAN_DISBURSEMENT, "LOAN-1",
                    new BigDecimal("30000"), new BigDecimal("10000"),
                    DepositHoldStatus.PARTIALLY_CAPTURED, now.plusDays(1), now, now, 3L);

            assertThat(hold.getId()).isEqualTo(9L);
            assertThat(hold.getOriginalAmount()).isEqualByComparingTo("30000");
            assertThat(hold.getRemainingAmount()).isEqualByComparingTo("10000");
            assertThat(hold.getStatus()).isEqualTo(DepositHoldStatus.PARTIALLY_CAPTURED);
            assertThat(hold.getVersion()).isEqualTo(3L);
        }

        @Test
        @DisplayName("assignId 는 최초 1회만 허용된다")
        void assignId_onlyOnce() {
            DepositHold hold = activeHold("10000");
            hold.assignId(11L);

            assertThat(hold.getId()).isEqualTo(11L);
            assertThatThrownBy(() -> hold.assignId(12L)).isInstanceOf(DepositInvariantViolationException.class);
        }
    }
}
