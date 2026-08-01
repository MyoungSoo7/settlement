package github.lms.lemuel.card.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimitSnapshotTest {

    @Test
    @DisplayName("재원(F) = 셀러 지급예정 + 홀드백 유보분")
    void fundingIsSum() {
        LimitSnapshot snapshot = new LimitSnapshot(
                new BigDecimal("200000.00"), new BigDecimal("50000.00"),
                new BigDecimal("0.70"), ReputationGrade.B, "floor(F x R x H)");

        assertThat(snapshot.funding()).isEqualByComparingTo("250000.00");
    }

    @Test
    @DisplayName("근거 없는 스냅샷은 만들 수 없다 — 필수값 결손 시 거부")
    void requiresAllEvidence() {
        assertThatThrownBy(() -> new LimitSnapshot(
                null, new BigDecimal("50000.00"), new BigDecimal("0.70"), ReputationGrade.B, "f"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
