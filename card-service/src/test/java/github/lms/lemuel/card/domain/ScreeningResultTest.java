package github.lms.lemuel.card.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * {@link ScreeningResult} 의 컴팩트 생성자 불변식 고정.
 *
 * <p>canonical 생성자가 public 이라 정적 팩토리({@code approved}/{@code rejected})를 거치지 않고도
 * 직접 인스턴스를 만들 수 있다. 승인인데 거절 사유가 있다거나, 탈락인데 masterLimit 이 0 이 아닌
 * 것 같은 모순된 조합을 컴파일러가 막지 못하므로 런타임에서라도 막아야 한다 — Task 9 유스케이스가
 * 팩토리를 쓰지 않고 canonical 생성자를 직접 호출하면 조용히 깨질 수 있기 때문이다.
 */
class ScreeningResultTest {

    private static LimitSnapshot snapshot() {
        return new LimitSnapshot(new BigDecimal("1000000"), BigDecimal.ZERO,
                new BigDecimal("0.70"), ReputationGrade.A, "formula");
    }

    @Test
    @DisplayName("승인인데 거절 사유가 있으면 거부한다")
    void approvedWithRejectReasonRejected() {
        assertThat(catchThrowable(() ->
                new ScreeningResult(true, new BigDecimal("700000"), snapshot(), "탈락사유")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("탈락인데 거절 사유가 null 이면 거부한다")
    void rejectedWithNullReasonRejected() {
        assertThat(catchThrowable(() ->
                new ScreeningResult(false, BigDecimal.ZERO, snapshot(), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("탈락인데 거절 사유가 공백뿐이면 거부한다")
    void rejectedWithBlankReasonRejected() {
        assertThat(catchThrowable(() ->
                new ScreeningResult(false, BigDecimal.ZERO, snapshot(), "   ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("탈락인데 masterLimit 이 0 이 아니면 거부한다")
    void rejectedWithNonZeroMasterLimitRejected() {
        assertThat(catchThrowable(() ->
                new ScreeningResult(false, new BigDecimal("1"), snapshot(), "사유")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("snapshot 이 null 이면 거부한다 — 근거 없는 판정을 남기지 않는다")
    void nullSnapshotRejected() {
        assertThat(catchThrowable(() ->
                new ScreeningResult(true, new BigDecimal("700000"), null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("masterLimit 이 null 이면 거부한다")
    void nullMasterLimitRejected() {
        assertThat(catchThrowable(() ->
                new ScreeningResult(true, null, snapshot(), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("masterLimit 이 음수면 거부한다")
    void negativeMasterLimitRejected() {
        assertThat(catchThrowable(() ->
                new ScreeningResult(true, new BigDecimal("-1"), snapshot(), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("정적 팩토리 approved() 는 여전히 정상 동작한다")
    void approvedFactoryStillWorks() {
        ScreeningResult r = ScreeningResult.approved(new BigDecimal("700000"), snapshot());

        assertThat(r.approved()).isTrue();
        assertThat(r.masterLimit()).isEqualByComparingTo("700000");
        assertThat(r.rejectReason()).isNull();
        assertThat(r.snapshot()).isNotNull();
    }

    @Test
    @DisplayName("정적 팩토리 rejected() 는 여전히 정상 동작한다")
    void rejectedFactoryStillWorks() {
        ScreeningResult r = ScreeningResult.rejected(snapshot(), "최소한도 미달");

        assertThat(r.approved()).isFalse();
        assertThat(r.masterLimit()).isEqualByComparingTo("0");
        assertThat(r.rejectReason()).isEqualTo("최소한도 미달");
        assertThat(r.snapshot()).isNotNull();
    }
}
