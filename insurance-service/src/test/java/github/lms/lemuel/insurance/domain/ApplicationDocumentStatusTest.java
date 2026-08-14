package github.lms.lemuel.insurance.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static github.lms.lemuel.insurance.domain.ApplicationDocumentStatus.EXTRACTED;
import static github.lms.lemuel.insurance.domain.ApplicationDocumentStatus.MATCHED;
import static github.lms.lemuel.insurance.domain.ApplicationDocumentStatus.MISMATCHED;
import static github.lms.lemuel.insurance.domain.ApplicationDocumentStatus.NEEDS_REVIEW;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 청약서류 대사 상태 전이표 — 종결 이후 번복은 새 서류 첨부로만 (ADR 0036 확산).
 */
class ApplicationDocumentStatusTest {

    @Test
    @DisplayName("EXTRACTED 는 대사 3귀결로만 전이한다")
    void extractedTransitions() {
        assertThat(EXTRACTED.canTransitionTo(MATCHED)).isTrue();
        assertThat(EXTRACTED.canTransitionTo(MISMATCHED)).isTrue();
        assertThat(EXTRACTED.canTransitionTo(NEEDS_REVIEW)).isTrue();
        assertThat(EXTRACTED.canTransitionTo(EXTRACTED)).isFalse();
    }

    @Test
    @DisplayName("NEEDS_REVIEW 는 관리자 리뷰로 종결로만 간다")
    void needsReviewTransitions() {
        assertThat(NEEDS_REVIEW.canTransitionTo(MATCHED)).isTrue();
        assertThat(NEEDS_REVIEW.canTransitionTo(MISMATCHED)).isTrue();
        assertThat(NEEDS_REVIEW.canTransitionTo(EXTRACTED)).isFalse();
        assertThat(NEEDS_REVIEW.canTransitionTo(NEEDS_REVIEW)).isFalse();
    }

    @Test
    @DisplayName("MATCHED·MISMATCHED 는 종결 — 어떤 전이도 불가")
    void terminalStates() {
        for (ApplicationDocumentStatus next : ApplicationDocumentStatus.values()) {
            assertThat(MATCHED.canTransitionTo(next)).isFalse();
            assertThat(MISMATCHED.canTransitionTo(next)).isFalse();
        }
        assertThat(MATCHED.isTerminal()).isTrue();
        assertThat(MISMATCHED.isTerminal()).isTrue();
        assertThat(EXTRACTED.isTerminal()).isFalse();
        assertThat(NEEDS_REVIEW.isTerminal()).isFalse();
    }
}
