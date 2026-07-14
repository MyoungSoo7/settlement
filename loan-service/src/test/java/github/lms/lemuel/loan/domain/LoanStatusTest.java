package github.lms.lemuel.loan.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전이표 단일 출처 직접 검증 — LoanAdvance 애그리거트 전이 메서드가 허용하는 집합과 동일해야 한다.
 * OVERDUE·WRITTEN_OFF 는 아직 도메인 전이 메서드가 없으므로 어떤 전이의 원천/대상도 아니다.
 */
class LoanStatusTest {

    @Test @DisplayName("허용 전이만 canTransitionTo=true")
    void canTransitionTo_allowed() {
        assertThat(LoanStatus.REQUESTED.canTransitionTo(LoanStatus.APPROVED)).isTrue();
        assertThat(LoanStatus.REQUESTED.canTransitionTo(LoanStatus.REJECTED)).isTrue();
        assertThat(LoanStatus.APPROVED.canTransitionTo(LoanStatus.DISBURSED)).isTrue();
        // LoanAdvance.reject 는 APPROVED 에서도 REJECTED 가능(CorporateLoan 과 다른 지점)
        assertThat(LoanStatus.APPROVED.canTransitionTo(LoanStatus.REJECTED)).isTrue();
        assertThat(LoanStatus.DISBURSED.canTransitionTo(LoanStatus.REPAID)).isTrue();
    }

    @Test @DisplayName("표에 없는 전이는 canTransitionTo=false")
    void canTransitionTo_disallowed() {
        assertThat(LoanStatus.REQUESTED.canTransitionTo(LoanStatus.DISBURSED)).isFalse();
        assertThat(LoanStatus.APPROVED.canTransitionTo(LoanStatus.REPAID)).isFalse();
        assertThat(LoanStatus.DISBURSED.canTransitionTo(LoanStatus.REJECTED)).isFalse();
        // 미구현/종료 상태는 어떤 전이도 불가
        for (LoanStatus target : LoanStatus.values()) {
            assertThat(LoanStatus.REPAID.canTransitionTo(target)).isFalse();
            assertThat(LoanStatus.REJECTED.canTransitionTo(target)).isFalse();
            assertThat(LoanStatus.OVERDUE.canTransitionTo(target)).isFalse();
            assertThat(LoanStatus.WRITTEN_OFF.canTransitionTo(target)).isFalse();
        }
    }
}
