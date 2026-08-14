package github.lms.lemuel.deposit.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static github.lms.lemuel.deposit.domain.DepositProofStatus.EXTRACTED;
import static github.lms.lemuel.deposit.domain.DepositProofStatus.MATCHED;
import static github.lms.lemuel.deposit.domain.DepositProofStatus.MISMATCHED;
import static github.lms.lemuel.deposit.domain.DepositProofStatus.NEEDS_REVIEW;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 예치금 증빙 대사 상태 전이표 — 종결 이후 번복은 새 증빙 첨부로만 (ADR 0036 확산).
 */
class DepositProofStatusTest {

    @Test
    @DisplayName("EXTRACTED(기표 대기)는 대사 3귀결로만 전이한다")
    void extractedTransitions() {
        assertThat(EXTRACTED.canTransitionTo(MATCHED)).isTrue();
        assertThat(EXTRACTED.canTransitionTo(MISMATCHED)).isTrue();
        assertThat(EXTRACTED.canTransitionTo(NEEDS_REVIEW)).isTrue();
        assertThat(EXTRACTED.canTransitionTo(EXTRACTED)).isFalse();
    }

    @Test
    @DisplayName("NEEDS_REVIEW 는 운영자 리뷰로 종결로만 간다")
    void needsReviewTransitions() {
        assertThat(NEEDS_REVIEW.canTransitionTo(MATCHED)).isTrue();
        assertThat(NEEDS_REVIEW.canTransitionTo(MISMATCHED)).isTrue();
        assertThat(NEEDS_REVIEW.canTransitionTo(EXTRACTED)).isFalse();
        assertThat(NEEDS_REVIEW.canTransitionTo(NEEDS_REVIEW)).isFalse();
    }

    @Test
    @DisplayName("MATCHED·MISMATCHED 는 종결 — 어떤 전이도 불가")
    void terminalStates() {
        for (DepositProofStatus next : DepositProofStatus.values()) {
            assertThat(MATCHED.canTransitionTo(next)).isFalse();
            assertThat(MISMATCHED.canTransitionTo(next)).isFalse();
        }
        assertThat(MATCHED.isTerminal()).isTrue();
        assertThat(MISMATCHED.isTerminal()).isTrue();
        assertThat(EXTRACTED.isTerminal()).isFalse();
        assertThat(NEEDS_REVIEW.isTerminal()).isFalse();
    }
}
