package github.lms.lemuel.recovery.domain;

import github.lms.lemuel.recovery.domain.exception.RecoveryInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 상계 이력 — (채권, 후속 정산) 축의 append-only 배분 레코드.
 * payout 축은 보관하지 않는다((settlement, IMMEDIATE) UNIQUE 로 역추적).
 */
class RecoveryAllocationTest {

    @Test
    @DisplayName("allocationOf — 정상 생성")
    void createsAllocation() {
        RecoveryAllocation allocation = RecoveryAllocation.allocationOf(
                99L, 501L, new BigDecimal("1000.00"));

        assertThat(allocation.recoveryId()).isEqualTo(99L);
        assertThat(allocation.settlementId()).isEqualTo(501L);
        assertThat(allocation.amount()).isEqualByComparingTo("1000.00");
        assertThat(allocation.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("금액 0·음수·null, 채권·정산 id null 은 타입 예외")
    void rejectsInvalidFields() {
        assertThatThrownBy(() -> RecoveryAllocation.allocationOf(99L, 501L, BigDecimal.ZERO))
                .isInstanceOf(RecoveryInvariantViolationException.class);
        assertThatThrownBy(() -> RecoveryAllocation.allocationOf(99L, 501L, new BigDecimal("-1")))
                .isInstanceOf(RecoveryInvariantViolationException.class);
        assertThatThrownBy(() -> RecoveryAllocation.allocationOf(99L, 501L, null))
                .isInstanceOf(RecoveryInvariantViolationException.class);
        assertThatThrownBy(() -> RecoveryAllocation.allocationOf(null, 501L, BigDecimal.ONE))
                .isInstanceOf(RecoveryInvariantViolationException.class);
        assertThatThrownBy(() -> RecoveryAllocation.allocationOf(99L, null, BigDecimal.ONE))
                .isInstanceOf(RecoveryInvariantViolationException.class);
    }

    @Test
    @DisplayName("rehydrate — 영속 복원 보존")
    void rehydratePreserves() {
        LocalDateTime created = LocalDateTime.of(2026, 7, 21, 9, 0);
        RecoveryAllocation allocation = RecoveryAllocation.rehydrate(
                1L, 99L, 501L, new BigDecimal("500.00"), created);

        assertThat(allocation.id()).isEqualTo(1L);
        assertThat(allocation.createdAt()).isEqualTo(created);
    }
}
