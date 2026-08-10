package github.lms.lemuel.settlement.domain.rerun;

import github.lms.lemuel.settlement.domain.exception.InvalidRerunRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 정산 배치 재실행 요청의 사전조건 — 운영자 실수(미래 일자·과도한 소급)를 도메인에서 차단한다.
 *
 * <p>배경: 재실행 API 를 여는 순간 "targetDate 를 1 년 전으로 넣어도 그대로 대량 실행된다"가
 * 실제 운영 위험이 된다. 게이트를 컨트롤러 검증이 아니라 도메인 팩토리에 두어 호출 경로가
 * 늘어나도(스케줄러·REST·향후 MCP) 동일하게 강제되게 한다.
 */
class SettlementRerunRequestTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 6);
    private static final int MAX_LOOKBACK_DAYS = 90;

    @Test
    @DisplayName("정상: 어제 일자 CONFIRM 재실행 요청 생성")
    void createsValidRequest() {
        LocalDate yesterday = TODAY.minusDays(1);

        SettlementRerunRequest request =
                SettlementRerunRequest.of(SettlementRerunScope.CONFIRM, yesterday, TODAY, MAX_LOOKBACK_DAYS);

        assertThat(request.scope()).isEqualTo(SettlementRerunScope.CONFIRM);
        assertThat(request.targetDate()).isEqualTo(yesterday);
        assertThat(request.steps()).containsExactly(SettlementRerunScope.CONFIRM);
    }

    @Test
    @DisplayName("경계: 오늘 일자는 허용 — 당일 재확정 수요가 있다")
    void allowsToday() {
        SettlementRerunRequest request =
                SettlementRerunRequest.of(SettlementRerunScope.CONFIRM, TODAY, TODAY, MAX_LOOKBACK_DAYS);

        assertThat(request.targetDate()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("미래 일자는 거부 — 아직 오지 않은 날의 정산을 확정할 수 없다")
    void rejectsFutureDate() {
        LocalDate tomorrow = TODAY.plusDays(1);

        assertThatThrownBy(() ->
                SettlementRerunRequest.of(SettlementRerunScope.CONFIRM, tomorrow, TODAY, MAX_LOOKBACK_DAYS))
                .isInstanceOf(InvalidRerunRequestException.class)
                .hasMessageContaining("미래");
    }

    @Test
    @DisplayName("경계: 최대 소급일 정확히 그날은 허용")
    void allowsExactlyMaxLookback() {
        LocalDate boundary = TODAY.minusDays(MAX_LOOKBACK_DAYS);

        SettlementRerunRequest request =
                SettlementRerunRequest.of(SettlementRerunScope.CONFIRM, boundary, TODAY, MAX_LOOKBACK_DAYS);

        assertThat(request.targetDate()).isEqualTo(boundary);
    }

    @Test
    @DisplayName("경계: 최대 소급일 + 1 일은 거부 — 대량 과거 재정산 사고 차단")
    void rejectsBeyondMaxLookback() {
        LocalDate tooOld = TODAY.minusDays(MAX_LOOKBACK_DAYS + 1L);

        assertThatThrownBy(() ->
                SettlementRerunRequest.of(SettlementRerunScope.CONFIRM, tooOld, TODAY, MAX_LOOKBACK_DAYS))
                .isInstanceOf(InvalidRerunRequestException.class)
                .hasMessageContaining("소급");
    }

    @Test
    @DisplayName("scope 누락은 거부")
    void rejectsNullScope() {
        assertThatThrownBy(() ->
                SettlementRerunRequest.of(null, TODAY, TODAY, MAX_LOOKBACK_DAYS))
                .isInstanceOf(InvalidRerunRequestException.class);
    }

    @Test
    @DisplayName("targetDate 누락은 거부 — 기본값 보정은 호출측(어제) 책임")
    void rejectsNullTargetDate() {
        assertThatThrownBy(() ->
                SettlementRerunRequest.of(SettlementRerunScope.CONFIRM, null, TODAY, MAX_LOOKBACK_DAYS))
                .isInstanceOf(InvalidRerunRequestException.class);
    }

    @Test
    @DisplayName("최대 소급일이 음수면 거부 — 설정 오류를 조용히 통과시키지 않는다")
    void rejectsNegativeMaxLookback() {
        assertThatThrownBy(() ->
                SettlementRerunRequest.of(SettlementRerunScope.CONFIRM, TODAY, TODAY, -1))
                .isInstanceOf(InvalidRerunRequestException.class);
    }

    @Test
    @DisplayName("ALL 은 재계산 경로(CONFIRM·HOLDBACK_RELEASE)만 전개 — 실자금 이동은 제외")
    void allExpandsToRecomputeStepsOnly() {
        SettlementRerunRequest request =
                SettlementRerunRequest.of(SettlementRerunScope.ALL, TODAY, TODAY, MAX_LOOKBACK_DAYS);

        assertThat(request.steps())
                .containsExactly(SettlementRerunScope.CONFIRM, SettlementRerunScope.HOLDBACK_RELEASE)
                .doesNotContain(SettlementRerunScope.PAYOUT_EXECUTE);
    }

    @Test
    @DisplayName("PAYOUT_EXECUTE 는 명시 선택으로만 실행 — 자금 이동 단계로 분류")
    void payoutExecuteIsMoneyMoving() {
        assertThat(SettlementRerunScope.PAYOUT_EXECUTE.movesMoney()).isTrue();
        assertThat(SettlementRerunScope.CONFIRM.movesMoney()).isFalse();
        assertThat(SettlementRerunScope.HOLDBACK_RELEASE.movesMoney()).isFalse();
        assertThat(SettlementRerunScope.ALL.movesMoney()).isFalse();

        SettlementRerunRequest request =
                SettlementRerunRequest.of(SettlementRerunScope.PAYOUT_EXECUTE, TODAY, TODAY, MAX_LOOKBACK_DAYS);
        assertThat(request.steps()).containsExactly(SettlementRerunScope.PAYOUT_EXECUTE);
    }

    @Test
    @DisplayName("PAYOUT_EXECUTE 는 날짜 무관 — 대상은 REQUESTED 상태 전량이다")
    void payoutExecuteIsNotDateScoped() {
        assertThat(SettlementRerunScope.PAYOUT_EXECUTE.dateScoped()).isFalse();
        assertThat(SettlementRerunScope.CONFIRM.dateScoped()).isTrue();
        assertThat(SettlementRerunScope.HOLDBACK_RELEASE.dateScoped()).isTrue();
    }
}
