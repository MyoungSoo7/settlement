package github.lms.lemuel.recovery.domain;

import github.lms.lemuel.recovery.domain.exception.InvalidRecoveryStateException;
import github.lms.lemuel.recovery.domain.exception.RecoveryInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 시드 P0-6 — 지급후 회수 채권 애그리거트.
 *
 * <p>COMPLETED Payout 정산의 회수분 중 holdback 으로 흡수되지 못한 잔여가 채권 원금이 된다.
 * 상계(allocate)는 도메인이 잔액 검증·CLOSED 전이를 소유한다 — 서비스단 sum 쿼리 검증 금지.
 */
class SellerRecoveryTest {

    private static final BigDecimal PRINCIPAL = new BigDecimal("3000.00");

    @Nested
    @DisplayName("open — 채권 발생")
    class Open {

        @Test
        @DisplayName("정상 발생: OPEN, 잔액=원금, 상계누적 0")
        void opensWithFullOutstanding() {
            SellerRecovery recovery = SellerRecovery.open(11L, 7L, PRINCIPAL);

            assertThat(recovery.getStatus()).isEqualTo(RecoveryStatus.OPEN);
            assertThat(recovery.getSourceAdjustmentId()).isEqualTo(11L);
            assertThat(recovery.getSellerId()).isEqualTo(7L);
            assertThat(recovery.getOriginalAmount()).isEqualByComparingTo(PRINCIPAL);
            assertThat(recovery.getAllocatedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(recovery.outstanding()).isEqualByComparingTo(PRINCIPAL);
            assertThat(recovery.getClosedAt()).isNull();
        }

        @Test
        @DisplayName("원금 0·음수·null, 식별자 null 은 타입 예외로 거절")
        void rejectsInvalidPrincipalAndIds() {
            assertThatThrownBy(() -> SellerRecovery.open(11L, 7L, BigDecimal.ZERO))
                    .isInstanceOf(RecoveryInvariantViolationException.class);
            assertThatThrownBy(() -> SellerRecovery.open(11L, 7L, new BigDecimal("-1")))
                    .isInstanceOf(RecoveryInvariantViolationException.class);
            assertThatThrownBy(() -> SellerRecovery.open(11L, 7L, null))
                    .isInstanceOf(RecoveryInvariantViolationException.class);
            assertThatThrownBy(() -> SellerRecovery.open(null, 7L, PRINCIPAL))
                    .isInstanceOf(RecoveryInvariantViolationException.class);
            assertThatThrownBy(() -> SellerRecovery.open(11L, null, PRINCIPAL))
                    .isInstanceOf(RecoveryInvariantViolationException.class);
        }
    }

    @Nested
    @DisplayName("allocate — 상계")
    class Allocate {

        @Test
        @DisplayName("부분 상계: 요청 전액 소비, OPEN 유지, 잔액 감소")
        void partiallyAllocates() {
            SellerRecovery recovery = SellerRecovery.open(11L, 7L, PRINCIPAL);

            BigDecimal consumed = recovery.allocate(new BigDecimal("1000.00"));

            assertThat(consumed).isEqualByComparingTo("1000.00");
            assertThat(recovery.getAllocatedAmount()).isEqualByComparingTo("1000.00");
            assertThat(recovery.outstanding()).isEqualByComparingTo("2000.00");
            assertThat(recovery.getStatus()).isEqualTo(RecoveryStatus.OPEN);
        }

        @Test
        @DisplayName("잔액 초과 요청은 잔액만큼만 소비한다 (초과분은 호출자의 다음 채권 몫)")
        void capsAtOutstanding() {
            SellerRecovery recovery = SellerRecovery.open(11L, 7L, PRINCIPAL);

            BigDecimal consumed = recovery.allocate(new BigDecimal("5000.00"));

            assertThat(consumed).isEqualByComparingTo(PRINCIPAL);
            assertThat(recovery.outstanding()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(recovery.getStatus()).isEqualTo(RecoveryStatus.CLOSED);
            assertThat(recovery.getClosedAt()).isNotNull();
        }

        @Test
        @DisplayName("전액 상계 도달 시 CLOSED 전이 + closedAt 기록")
        void closesWhenFullyAllocated() {
            SellerRecovery recovery = SellerRecovery.open(11L, 7L, PRINCIPAL);

            recovery.allocate(new BigDecimal("3000.00"));

            assertThat(recovery.getStatus()).isEqualTo(RecoveryStatus.CLOSED);
            assertThat(recovery.outstanding()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("0·음수 요청은 타입 예외")
        void rejectsNonPositiveRequest() {
            SellerRecovery recovery = SellerRecovery.open(11L, 7L, PRINCIPAL);

            assertThatThrownBy(() -> recovery.allocate(BigDecimal.ZERO))
                    .isInstanceOf(RecoveryInvariantViolationException.class);
            assertThatThrownBy(() -> recovery.allocate(new BigDecimal("-100")))
                    .isInstanceOf(RecoveryInvariantViolationException.class);
        }

        @Test
        @DisplayName("CLOSED·MANUAL_REQUIRED 에서 상계 시도는 상태 예외 (종결·수기 이관 불변)")
        void rejectsAllocateAfterTerminalOrManual() {
            SellerRecovery closed = SellerRecovery.open(11L, 7L, PRINCIPAL);
            closed.allocate(PRINCIPAL);
            assertThatThrownBy(() -> closed.allocate(new BigDecimal("1")))
                    .isInstanceOf(InvalidRecoveryStateException.class);

            SellerRecovery manual = SellerRecovery.open(12L, 7L, PRINCIPAL);
            manual.markManualRequired();
            assertThatThrownBy(() -> manual.allocate(new BigDecimal("1")))
                    .isInstanceOf(InvalidRecoveryStateException.class);
        }
    }

    @Nested
    @DisplayName("markManualRequired — 수기 이관")
    class ManualRequired {

        @Test
        @DisplayName("OPEN → MANUAL_REQUIRED 전이")
        void marksManual() {
            SellerRecovery recovery = SellerRecovery.open(11L, 7L, PRINCIPAL);

            recovery.markManualRequired();

            assertThat(recovery.getStatus()).isEqualTo(RecoveryStatus.MANUAL_REQUIRED);
        }

        @Test
        @DisplayName("CLOSED 에서 수기 이관 시도는 상태 예외")
        void rejectsManualAfterClosed() {
            SellerRecovery recovery = SellerRecovery.open(11L, 7L, PRINCIPAL);
            recovery.allocate(PRINCIPAL);

            assertThatThrownBy(recovery::markManualRequired)
                    .isInstanceOf(InvalidRecoveryStateException.class);
        }
    }

    @Test
    @DisplayName("rehydrate — 영속 복원은 필드를 그대로 보존한다")
    void rehydratePreservesFields() {
        LocalDateTime created = LocalDateTime.of(2026, 7, 20, 10, 0);
        SellerRecovery recovery = SellerRecovery.rehydrate(
                99L, 11L, 7L, PRINCIPAL, new BigDecimal("1000.00"),
                RecoveryStatus.OPEN, created, null);

        assertThat(recovery.getId()).isEqualTo(99L);
        assertThat(recovery.outstanding()).isEqualByComparingTo("2000.00");
        assertThat(recovery.getCreatedAt()).isEqualTo(created);
    }

    @Test
    @DisplayName("RecoveryStatus 전이표 — OPEN→{MANUAL_REQUIRED, CLOSED}, MANUAL_REQUIRED→CLOSED, CLOSED 는 종결")
    void statusTransitionTable() {
        assertThat(RecoveryStatus.OPEN.canTransitionTo(RecoveryStatus.MANUAL_REQUIRED)).isTrue();
        assertThat(RecoveryStatus.OPEN.canTransitionTo(RecoveryStatus.CLOSED)).isTrue();
        assertThat(RecoveryStatus.MANUAL_REQUIRED.canTransitionTo(RecoveryStatus.CLOSED)).isTrue();
        assertThat(RecoveryStatus.MANUAL_REQUIRED.canTransitionTo(RecoveryStatus.OPEN)).isFalse();
        assertThat(RecoveryStatus.CLOSED.canTransitionTo(RecoveryStatus.OPEN)).isFalse();
        assertThat(RecoveryStatus.CLOSED.canTransitionTo(RecoveryStatus.MANUAL_REQUIRED)).isFalse();
    }
}
