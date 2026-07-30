package github.lms.lemuel.loan.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 담보 설정/해지 상태 전이표 전수 검증. 전이 허용 여부의 단일 출처가 이 enum 이므로,
 * 애그리거트 가드가 아니라 여기서 경계를 못 박는다.
 */
class CollateralStatusTest {

    @Test
    void 설정중은_유효_또는_말소로만_간다() {
        assertThat(CollateralStatus.PLEDGED.canTransitionTo(CollateralStatus.ACTIVE)).isTrue();
        assertThat(CollateralStatus.PLEDGED.canTransitionTo(CollateralStatus.RELEASED)).isTrue();
        assertThat(CollateralStatus.PLEDGED.canTransitionTo(CollateralStatus.PLEDGED)).isFalse();
    }

    @Test
    void 유효는_말소로만_간다() {
        assertThat(CollateralStatus.ACTIVE.canTransitionTo(CollateralStatus.RELEASED)).isTrue();
        assertThat(CollateralStatus.ACTIVE.canTransitionTo(CollateralStatus.PLEDGED)).isFalse();
        assertThat(CollateralStatus.ACTIVE.canTransitionTo(CollateralStatus.ACTIVE)).isFalse();
    }

    @Test
    void 말소는_종료상태라_어디로도_못_간다() {
        for (CollateralStatus target : CollateralStatus.values()) {
            assertThat(CollateralStatus.RELEASED.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    void 모든_상태쌍이_전이표에_정의되어_있다() {
        for (CollateralStatus from : CollateralStatus.values()) {
            for (CollateralStatus to : CollateralStatus.values()) {
                // 예외 없이 판정되어야 한다(누락 분기로 인한 런타임 실패 차단).
                from.canTransitionTo(to);
            }
        }
    }
}
