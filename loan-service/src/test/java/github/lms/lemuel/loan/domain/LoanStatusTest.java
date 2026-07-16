package github.lms.lemuel.loan.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전이표 단일 출처 직접 검증 — LoanAdvance 애그리거트 전이 메서드가 허용하는 집합과 동일해야 한다.
 * OVERDUE 는 markOverdue(), WRITTEN_OFF 는 writeOff() 의 대상/원천이다.
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
        // 연체·상각 경로
        assertThat(LoanStatus.DISBURSED.canTransitionTo(LoanStatus.OVERDUE)).isTrue();
        assertThat(LoanStatus.OVERDUE.canTransitionTo(LoanStatus.REPAID)).isTrue();
        assertThat(LoanStatus.OVERDUE.canTransitionTo(LoanStatus.WRITTEN_OFF)).isTrue();
    }

    @Test @DisplayName("표에 없는 전이는 canTransitionTo=false")
    void canTransitionTo_disallowed() {
        assertThat(LoanStatus.REQUESTED.canTransitionTo(LoanStatus.DISBURSED)).isFalse();
        assertThat(LoanStatus.APPROVED.canTransitionTo(LoanStatus.REPAID)).isFalse();
        assertThat(LoanStatus.DISBURSED.canTransitionTo(LoanStatus.REJECTED)).isFalse();
        assertThat(LoanStatus.DISBURSED.canTransitionTo(LoanStatus.WRITTEN_OFF)).isFalse();  // 연체 없이 바로 상각 불가
        assertThat(LoanStatus.OVERDUE.canTransitionTo(LoanStatus.DISBURSED)).isFalse();      // 역행 불가
        assertThat(LoanStatus.OVERDUE.canTransitionTo(LoanStatus.APPROVED)).isFalse();
        // 종료 상태는 어떤 전이도 불가
        for (LoanStatus target : LoanStatus.values()) {
            assertThat(LoanStatus.REPAID.canTransitionTo(target)).isFalse();
            assertThat(LoanStatus.REJECTED.canTransitionTo(target)).isFalse();
            assertThat(LoanStatus.WRITTEN_OFF.canTransitionTo(target)).isFalse();
        }
    }
}
