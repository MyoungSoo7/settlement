package github.lms.lemuel.payout.application.service;

import github.lms.lemuel.payout.application.port.out.LoadPayoutPort;
import github.lms.lemuel.payout.application.service.PayoutLimitChecker.Decision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PayoutLimitChecker — 셀러/시스템 일 한도 검사")
class PayoutLimitCheckerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private static final BigDecimal SYSTEM_LIMIT = new BigDecimal("1000000");
    private static final BigDecimal SELLER_LIMIT = new BigDecimal("100000");

    @Mock LoadPayoutPort loadPort;
    PayoutLimitChecker checker;

    @BeforeEach
    void setUp() {
        checker = new PayoutLimitChecker(loadPort, SYSTEM_LIMIT, SELLER_LIMIT);
    }

    @Test
    @DisplayName("한도 이내면 allowed=true")
    void allowed() {
        when(loadPort.sumCompletedBySellerOn(1L, TODAY)).thenReturn(new BigDecimal("10000"));
        when(loadPort.sumCompletedSystemwideOn(TODAY)).thenReturn(new BigDecimal("50000"));

        Decision decision = checker.canSend(1L, new BigDecimal("20000"), TODAY);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).isNull();
    }

    @Test
    @DisplayName("셀러 일 한도 초과 시 allowed=false + 사유 (시스템 한도는 조회조차 안 함)")
    void sellerLimitExceeded() {
        when(loadPort.sumCompletedBySellerOn(1L, TODAY)).thenReturn(new BigDecimal("90000"));

        Decision decision = checker.canSend(1L, new BigDecimal("20000"), TODAY);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("셀러 일 한도 초과");
    }

    @Test
    @DisplayName("셀러는 통과했지만 시스템 일 한도를 넘으면 allowed=false")
    void systemLimitExceeded() {
        when(loadPort.sumCompletedBySellerOn(1L, TODAY)).thenReturn(new BigDecimal("10000"));
        when(loadPort.sumCompletedSystemwideOn(TODAY)).thenReturn(new BigDecimal("999000"));

        Decision decision = checker.canSend(1L, new BigDecimal("20000"), TODAY);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("시스템 일 한도 초과");
    }

    @Test
    @DisplayName("경계값: 누적+요청 == 한도 는 통과 (초과 아님)")
    void exactlyAtLimitIsAllowed() {
        lenient().when(loadPort.sumCompletedBySellerOn(eq(1L), any())).thenReturn(new BigDecimal("80000"));
        lenient().when(loadPort.sumCompletedSystemwideOn(any())).thenReturn(BigDecimal.ZERO);

        Decision decision = checker.canSend(1L, new BigDecimal("20000"), TODAY);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    @DisplayName("미리보기 누적 반영: 같은 배치에서 앞 건이 소진한 셀러 한도를 뒤 건이 본다")
    void projectedSellerAccumulationIsHonored() {
        lenient().when(loadPort.sumCompletedBySellerOn(eq(1L), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(loadPort.sumCompletedSystemwideOn(any())).thenReturn(BigDecimal.ZERO);

        // 한도 100,000 · 앞 건이 이미 90,000 을 쓸 예정이면 20,000 건은 나갈 수 없다.
        Decision decision = checker.canSend(1L, new BigDecimal("20000"), TODAY,
                new BigDecimal("90000"), BigDecimal.ZERO);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("셀러 일 한도 초과");
    }

    @Test
    @DisplayName("미리보기 누적 반영: 시스템 한도도 앞 건들의 예정액을 함께 센다")
    void projectedSystemAccumulationIsHonored() {
        lenient().when(loadPort.sumCompletedBySellerOn(anyLong(), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(loadPort.sumCompletedSystemwideOn(any())).thenReturn(BigDecimal.ZERO);

        Decision decision = checker.canSend(9L, new BigDecimal("10000"), TODAY,
                BigDecimal.ZERO, SYSTEM_LIMIT);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("시스템 일 한도 초과");
    }

    @Test
    @DisplayName("누적 0 이면 기존 판정과 동일하다(실행 경로 동작 불변)")
    void zeroProjectionMatchesLegacyDecision() {
        lenient().when(loadPort.sumCompletedBySellerOn(eq(1L), any())).thenReturn(new BigDecimal("80000"));
        lenient().when(loadPort.sumCompletedSystemwideOn(any())).thenReturn(BigDecimal.ZERO);

        assertThat(checker.canSend(1L, new BigDecimal("20000"), TODAY).allowed())
                .isEqualTo(checker.canSend(1L, new BigDecimal("20000"), TODAY,
                        BigDecimal.ZERO, BigDecimal.ZERO).allowed());
    }

    @Test
    @DisplayName("구성된 한도 게터 노출")
    void limitGetters() {
        assertThat(checker.getSystemDailyLimit()).isEqualByComparingTo(SYSTEM_LIMIT);
        assertThat(checker.getSellerDailyLimit()).isEqualByComparingTo(SELLER_LIMIT);
    }
}
