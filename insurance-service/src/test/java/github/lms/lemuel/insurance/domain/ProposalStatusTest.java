package github.lms.lemuel.insurance.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static github.lms.lemuel.insurance.domain.ProposalStatus.CONVERTED;
import static github.lms.lemuel.insurance.domain.ProposalStatus.EXPIRED;
import static github.lms.lemuel.insurance.domain.ProposalStatus.QUOTED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가입설계 상태머신 전이표 전수 검증 — 허용 2개 외 모든 조합은 거짓이어야 한다.
 */
@DisplayName("ProposalStatus — 전이표 (QUOTED→CONVERTED|EXPIRED 만 허용)")
class ProposalStatusTest {

    @Test
    @DisplayName("허용 전이는 QUOTED→CONVERTED, QUOTED→EXPIRED 2개뿐이다")
    void allowedTransitions() {
        assertThat(QUOTED.canTransitionTo(CONVERTED)).isTrue();
        assertThat(QUOTED.canTransitionTo(EXPIRED)).isTrue();
    }

    @Test
    @DisplayName("그 외 모든 조합(자기 자신 포함)은 차단된다")
    void allOtherTransitionsBlocked() {
        for (ProposalStatus from : ProposalStatus.values()) {
            for (ProposalStatus to : ProposalStatus.values()) {
                boolean allowed = from == QUOTED && (to == CONVERTED || to == EXPIRED);
                assertThat(from.canTransitionTo(to))
                        .as("%s → %s", from, to)
                        .isEqualTo(allowed);
            }
        }
    }

    @Test
    @DisplayName("CONVERTED·EXPIRED 는 terminal, QUOTED 는 아니다")
    void terminalStates() {
        assertThat(QUOTED.isTerminal()).isFalse();
        assertThat(CONVERTED.isTerminal()).isTrue();
        assertThat(EXPIRED.isTerminal()).isTrue();
    }
}
