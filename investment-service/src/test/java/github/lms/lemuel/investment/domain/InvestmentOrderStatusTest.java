package github.lms.lemuel.investment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전이표 단일 출처 직접 검증 — InvestmentOrder 애그리거트 전이 메서드가 허용하는 집합과 동일해야 한다.
 */
class InvestmentOrderStatusTest {

    @Test @DisplayName("허용 전이만 canTransitionTo=true")
    void canTransitionTo_allowed() {
        assertThat(InvestmentOrderStatus.REQUESTED.canTransitionTo(InvestmentOrderStatus.APPROVED)).isTrue();
        assertThat(InvestmentOrderStatus.REQUESTED.canTransitionTo(InvestmentOrderStatus.REJECTED)).isTrue();
        assertThat(InvestmentOrderStatus.REQUESTED.canTransitionTo(InvestmentOrderStatus.CANCELED)).isTrue();
        assertThat(InvestmentOrderStatus.APPROVED.canTransitionTo(InvestmentOrderStatus.EXECUTED)).isTrue();
        assertThat(InvestmentOrderStatus.APPROVED.canTransitionTo(InvestmentOrderStatus.CANCELED)).isTrue();
    }

    @Test @DisplayName("표에 없는 전이는 canTransitionTo=false")
    void canTransitionTo_disallowed() {
        assertThat(InvestmentOrderStatus.REQUESTED.canTransitionTo(InvestmentOrderStatus.EXECUTED)).isFalse();
        // APPROVED 는 REJECTED 로 갈 수 없다(취소만 가능)
        assertThat(InvestmentOrderStatus.APPROVED.canTransitionTo(InvestmentOrderStatus.REJECTED)).isFalse();
        assertThat(InvestmentOrderStatus.APPROVED.canTransitionTo(InvestmentOrderStatus.APPROVED)).isFalse();
        // 종료 상태는 어떤 전이도 불가
        for (InvestmentOrderStatus target : InvestmentOrderStatus.values()) {
            assertThat(InvestmentOrderStatus.EXECUTED.canTransitionTo(target)).isFalse();
            assertThat(InvestmentOrderStatus.REJECTED.canTransitionTo(target)).isFalse();
            assertThat(InvestmentOrderStatus.CANCELED.canTransitionTo(target)).isFalse();
        }
    }
}
