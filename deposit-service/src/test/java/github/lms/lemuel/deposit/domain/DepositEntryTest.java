package github.lms.lemuel.deposit.domain;

import github.lms.lemuel.deposit.domain.exception.DepositInvariantViolationException;
import github.lms.lemuel.deposit.domain.exception.InvalidDepositAmountException;
import github.lms.lemuel.deposit.domain.exception.InvalidDepositStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

/**
 * DepositEntry 원장 엔트리 단위 테스트.
 *
 * <p>핵심 검증: OFFSET 엔트리의 {@code sourceHoldId} 가 null 이면
 * "hold 없는 늦은 청구" 를 나타내는 감사 표식이라는 계약.
 */
class DepositEntryTest {

    private static final Long ACCOUNT_ID = 5L;

    @Test
    @DisplayName("of 는 offsetSequence 0, sourceHoldId null 인 일반 엔트리를 만든다")
    void of_createsPlainEntry() {
        DepositEntry entry = DepositEntry.of(ACCOUNT_ID, DepositEntryType.CREDIT,
                new BigDecimal("100000"), "STL-1", "SETTLEMENT");

        assertThat(entry.getId()).isNull();
        assertThat(entry.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(entry.getEntryType()).isEqualTo(DepositEntryType.CREDIT);
        assertThat(entry.getAmount()).isEqualByComparingTo("100000");
        assertThat(entry.getReferenceId()).isEqualTo("STL-1");
        assertThat(entry.getReferenceType()).isEqualTo("SETTLEMENT");
        assertThat(entry.getOffsetSequence()).isZero();
        assertThat(entry.getSourceHoldId()).isNull();
        assertThat(entry.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("ofOffset 은 hold 기반 상계에 sourceHoldId 를 남긴다")
    void ofOffset_withSourceHold() {
        DepositEntry entry = DepositEntry.ofOffset(ACCOUNT_ID, new BigDecimal("30000"),
                "AUTH-1", "CARD_AUTHORIZATION", 2, 77L);

        assertThat(entry.getEntryType()).isEqualTo(DepositEntryType.OFFSET);
        assertThat(entry.getOffsetSequence()).isEqualTo(2);
        assertThat(entry.getSourceHoldId()).isEqualTo(77L);
    }

    @Test
    @DisplayName("ofOffset 의 sourceHoldId null 은 hold 없는 늦은 청구 상계를 뜻한다")
    void ofOffset_nullSourceHoldMarksLateCapture() {
        DepositEntry entry = DepositEntry.ofOffset(ACCOUNT_ID, new BigDecimal("30000"),
                "AUTH-2", "CARD_AUTHORIZATION", 0, null);

        assertThat(entry.getEntryType()).isEqualTo(DepositEntryType.OFFSET);
        assertThat(entry.getSourceHoldId()).isNull();
    }

    @Test
    @DisplayName("accountId·entryType·amount 는 필수다")
    void requiredFields() {
        assertThatThrownBy(() -> DepositEntry.of(null, DepositEntryType.CREDIT,
                BigDecimal.ONE, "R", "T")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DepositEntry.of(ACCOUNT_ID, null,
                BigDecimal.ONE, "R", "T")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DepositEntry.of(ACCOUNT_ID, DepositEntryType.CREDIT,
                null, "R", "T")).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rehydrate 는 영속 상태를 그대로 복원한다")
    void rehydrate_restoresState() {
        LocalDateTime created = LocalDateTime.now().minusDays(1);

        DepositEntry entry = DepositEntry.rehydrate(3L, ACCOUNT_ID, DepositEntryType.RELEASE,
                new BigDecimal("500"), "AUTH-9", "CARD_AUTHORIZATION", 1, 4L, created);

        assertThat(entry.getId()).isEqualTo(3L);
        assertThat(entry.getEntryType()).isEqualTo(DepositEntryType.RELEASE);
        assertThat(entry.getOffsetSequence()).isEqualTo(1);
        assertThat(entry.getSourceHoldId()).isEqualTo(4L);
        assertThat(entry.getCreatedAt()).isEqualTo(created);
    }

    @Test
    @DisplayName("assignId 는 최초 1회만 허용된다")
    void assignId_onlyOnce() {
        DepositEntry entry = DepositEntry.of(ACCOUNT_ID, DepositEntryType.DEBIT,
                BigDecimal.TEN, "P-1", "PAYOUT");
        entry.assignId(1L);

        assertThat(entry.getId()).isEqualTo(1L);
        assertThatThrownBy(() -> entry.assignId(2L)).isInstanceOf(DepositInvariantViolationException.class);
    }
}
