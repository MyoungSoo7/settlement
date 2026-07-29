package github.lms.lemuel.loan.domain;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 담보/개인신용 대출 상태 전이표 전수 검증.
 *
 * <p>기존 두 대출과 달리 <b>연체·기한이익상실</b>이 처음부터 상태머신에 들어가 있다 — 장기 분할상환
 * 상품이라 회차 미납이 정상 흐름의 일부이기 때문이다. 전이 허용 여부의 단일 출처가 이 enum 이므로
 * 여기서 전 조합을 못 박는다.
 */
class SecuredLoanStatusTest {

    /** 상태별 허용 목적지 정본 — 이 표에 없는 전이는 전부 거부되어야 한다. */
    private static Set<SecuredLoanStatus> allowedFrom(SecuredLoanStatus from) {
        return switch (from) {
            case REQUESTED -> EnumSet.of(SecuredLoanStatus.APPROVED, SecuredLoanStatus.REJECTED);
            case APPROVED -> EnumSet.of(SecuredLoanStatus.DISBURSED, SecuredLoanStatus.REJECTED);
            case DISBURSED -> EnumSet.of(SecuredLoanStatus.REPAID, SecuredLoanStatus.OVERDUE);
            case OVERDUE -> EnumSet.of(SecuredLoanStatus.REPAID, SecuredLoanStatus.DEFAULTED);
            // 기한이익상실 이후 담보 실행 결과: 전액 회수면 완제, 부족분이 남으면 상각.
            case DEFAULTED -> EnumSet.of(SecuredLoanStatus.REPAID, SecuredLoanStatus.WRITTEN_OFF);
            case REPAID, REJECTED, WRITTEN_OFF -> EnumSet.noneOf(SecuredLoanStatus.class);
        };
    }

    @Test
    void 전이표_전조합이_정본과_일치한다() {
        for (SecuredLoanStatus from : SecuredLoanStatus.values()) {
            Set<SecuredLoanStatus> allowed = allowedFrom(from);
            for (SecuredLoanStatus to : SecuredLoanStatus.values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s → %s", from, to)
                        .isEqualTo(allowed.contains(to));
            }
        }
    }

    @Test
    void 상각은_담보실행_이후에만_도달한다() {
        // 실행 전·연체 중에는 상각할 수 없다 — 담보 실행으로 회수 부족이 확정돼야 손실이 성립한다.
        assertThat(SecuredLoanStatus.DISBURSED.canTransitionTo(SecuredLoanStatus.WRITTEN_OFF)).isFalse();
        assertThat(SecuredLoanStatus.OVERDUE.canTransitionTo(SecuredLoanStatus.WRITTEN_OFF)).isFalse();
        assertThat(SecuredLoanStatus.DEFAULTED.canTransitionTo(SecuredLoanStatus.WRITTEN_OFF)).isTrue();
    }

    @Test
    void 종료상태는_어디로도_가지_못한다() {
        for (SecuredLoanStatus terminal : EnumSet.of(SecuredLoanStatus.REPAID,
                SecuredLoanStatus.REJECTED, SecuredLoanStatus.WRITTEN_OFF)) {
            for (SecuredLoanStatus target : SecuredLoanStatus.values()) {
                assertThat(terminal.canTransitionTo(target)).as("%s → %s", terminal, target).isFalse();
            }
        }
    }

    @Test
    void 실행전에는_연체될_수_없다() {
        assertThat(SecuredLoanStatus.REQUESTED.canTransitionTo(SecuredLoanStatus.OVERDUE)).isFalse();
        assertThat(SecuredLoanStatus.APPROVED.canTransitionTo(SecuredLoanStatus.OVERDUE)).isFalse();
    }

    @Test
    void 연체를_거치지_않고_기한이익상실될_수_없다() {
        assertThat(SecuredLoanStatus.DISBURSED.canTransitionTo(SecuredLoanStatus.DEFAULTED)).isFalse();
    }

    @Test
    void 기한이익상실_후에도_전액회수되면_완제된다() {
        assertThat(SecuredLoanStatus.DEFAULTED.canTransitionTo(SecuredLoanStatus.REPAID)).isTrue();
    }
}
