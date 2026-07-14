package github.lms.lemuel.loan.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전이표 단일 출처 직접 검증 — CorporateLoan 애그리거트 전이 메서드가 허용하는 집합과 동일해야 한다.
 */
class CorporateLoanStatusTest {

    @Test @DisplayName("허용 전이만 canTransitionTo=true")
    void canTransitionTo_allowed() {
        assertThat(CorporateLoanStatus.REQUESTED.canTransitionTo(CorporateLoanStatus.APPROVED)).isTrue();
        assertThat(CorporateLoanStatus.REQUESTED.canTransitionTo(CorporateLoanStatus.REJECTED)).isTrue();
        assertThat(CorporateLoanStatus.APPROVED.canTransitionTo(CorporateLoanStatus.DISBURSED)).isTrue();
        assertThat(CorporateLoanStatus.DISBURSED.canTransitionTo(CorporateLoanStatus.REPAID)).isTrue();
    }

    @Test @DisplayName("표에 없는 전이는 canTransitionTo=false")
    void canTransitionTo_disallowed() {
        assertThat(CorporateLoanStatus.REQUESTED.canTransitionTo(CorporateLoanStatus.DISBURSED)).isFalse();
        assertThat(CorporateLoanStatus.APPROVED.canTransitionTo(CorporateLoanStatus.REPAID)).isFalse();
        assertThat(CorporateLoanStatus.APPROVED.canTransitionTo(CorporateLoanStatus.REJECTED)).isFalse();
        // 종료 상태는 어떤 전이도 불가
        for (CorporateLoanStatus target : CorporateLoanStatus.values()) {
            assertThat(CorporateLoanStatus.REPAID.canTransitionTo(target)).isFalse();
            assertThat(CorporateLoanStatus.REJECTED.canTransitionTo(target)).isFalse();
        }
    }
}
