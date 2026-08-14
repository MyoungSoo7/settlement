package github.lms.lemuel.loan.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 리스 계약 상태 전이표 전수 검증 — 표에 없는 전이가 하나라도 열리면 실패한다.
 *
 * <p>전이표는 애그리거트가 위임하는 단일 출처라, 여기서 막히지 않은 전이는 실제 계약에서도 열린다.
 */
class LeaseStatusTest {

    /** 허용 전이 정본 — 상태표와 같은 내용을 <b>독립적으로</b> 다시 적어 대조한다. */
    private static final Map<LeaseStatus, Set<LeaseStatus>> ALLOWED = Map.of(
            LeaseStatus.APPLIED, EnumSet.of(LeaseStatus.APPROVED, LeaseStatus.REJECTED),
            LeaseStatus.APPROVED, EnumSet.of(LeaseStatus.ACTIVE, LeaseStatus.CANCELLED),
            LeaseStatus.ACTIVE, EnumSet.of(LeaseStatus.MATURED, LeaseStatus.OVERDUE, LeaseStatus.EARLY_TERMINATED),
            LeaseStatus.OVERDUE, EnumSet.of(LeaseStatus.ACTIVE, LeaseStatus.DEFAULTED,
                    LeaseStatus.EARLY_TERMINATED, LeaseStatus.MATURED),
            LeaseStatus.DEFAULTED, EnumSet.of(LeaseStatus.EARLY_TERMINATED),
            LeaseStatus.MATURED, EnumSet.noneOf(LeaseStatus.class),
            LeaseStatus.EARLY_TERMINATED, EnumSet.noneOf(LeaseStatus.class),
            LeaseStatus.REJECTED, EnumSet.noneOf(LeaseStatus.class),
            LeaseStatus.CANCELLED, EnumSet.noneOf(LeaseStatus.class));

    @ParameterizedTest
    @EnumSource(LeaseStatus.class)
    @DisplayName("전이표는 정본과 정확히 일치한다 (전 상태 × 전 상태)")
    void transitionTableMatchesSpecification(LeaseStatus from) {
        for (LeaseStatus to : LeaseStatus.values()) {
            assertThat(from.canTransitionTo(to))
                    .as("%s → %s", from, to)
                    .isEqualTo(ALLOWED.get(from).contains(to));
        }
    }

    @Test
    @DisplayName("기한이익상실은 연체를 거쳐야만 도달한다")
    void defaultReachableOnlyFromOverdue() {
        for (LeaseStatus from : LeaseStatus.values()) {
            assertThat(from.canTransitionTo(LeaseStatus.DEFAULTED))
                    .as("%s → DEFAULTED", from)
                    .isEqualTo(from == LeaseStatus.OVERDUE);
        }
    }

    @Test
    @DisplayName("종료 상태는 어디로도 가지 않는다")
    void terminalStatesAreClosed() {
        for (LeaseStatus status : LeaseStatus.values()) {
            if (!status.isTerminal()) continue;
            for (LeaseStatus target : LeaseStatus.values()) {
                assertThat(status.canTransitionTo(target)).as("%s → %s", status, target).isFalse();
            }
        }
    }

    @Test
    @DisplayName("리스료 청구는 개시·연체 상태에서만 살아 있다")
    void billableOnlyWhileActiveOrOverdue() {
        for (LeaseStatus status : LeaseStatus.values()) {
            assertThat(status.isBillable())
                    .as("%s", status)
                    .isEqualTo(status == LeaseStatus.ACTIVE || status == LeaseStatus.OVERDUE);
        }
    }
}
