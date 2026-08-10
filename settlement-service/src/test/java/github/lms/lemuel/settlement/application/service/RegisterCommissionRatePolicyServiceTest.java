package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.in.RegisterCommissionRatePolicyUseCase.RegisterPolicyCommand;
import github.lms.lemuel.settlement.application.port.out.SaveCommissionRatePolicyPort;
import github.lms.lemuel.settlement.domain.RateScope;
import github.lms.lemuel.settlement.domain.exception.RetroactiveRatePolicyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 요율 정책 등록 — 소급 가드 (ADR 0032 결정 ⑤).
 *
 * <p>소급을 날짜가 아니라 <b>데이터</b>로 판정한다. 이미 정산이 생성된 구간은 스냅샷이라 재계산되지
 * 않으므로 정책만 바꾸면 장부와 어긋난다 — 거기는 막는다. 그러나 계약은 8/1부터인데 등록이 8/7로
 * 늦어진 경우처럼 <b>그 구간에 정산이 아직 없으면</b> 소급 등록이 장부를 어긋나게 하지 않는다.
 * 무조건 차단하면 이 정상 사례까지 막아 운영자가 DB 를 직접 만지는 쪽으로 샌다.
 */
@ExtendWith(MockitoExtension.class)
class RegisterCommissionRatePolicyServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 7);

    @Mock SaveCommissionRatePolicyPort savePort;
    @Mock github.lms.lemuel.settlement.application.port.out.CountSettlementsInPeriodPort countPort;
    @InjectMocks RegisterCommissionRatePolicyService service;

    private RegisterPolicyCommand command(LocalDate from, LocalDate to) {
        return new RegisterPolicyCommand(RateScope.SELLER, "77", new BigDecimal("0.01800"),
                from, to, "계약 갱신", "admin");
    }

    @Test @DisplayName("미래 발효는 정산 조회 없이 통과한다")
    void futureEffectiveFrom_isAllowed() {
        service.register(command(TODAY.plusDays(1), null), TODAY);

        verify(savePort).save(any());
        verify(countPort, never()).countInPeriod(any(), any(), any(), any());
    }

    @Test @DisplayName("오늘 발효도 소급이 아니다")
    void todayIsNotRetroactive() {
        service.register(command(TODAY, null), TODAY);

        verify(savePort).save(any());
        verify(countPort, never()).countInPeriod(any(), any(), any(), any());
    }

    @Test @DisplayName("소급이어도 그 구간에 정산이 없으면 허용한다 — 늦게 등록된 계약")
    void retroactiveWithoutSettlements_isAllowed() {
        when(countPort.countInPeriod(any(), any(), any(), any())).thenReturn(0L);

        assertThatCode(() -> service.register(command(TODAY.minusDays(6), null), TODAY))
                .doesNotThrowAnyException();
        verify(savePort).save(any());
    }

    @Test @DisplayName("소급 구간에 정산이 이미 있으면 거부한다 — 장부와 정책이 어긋난다")
    void retroactiveWithSettlements_isRejected() {
        when(countPort.countInPeriod(any(), any(), any(), any())).thenReturn(3L);

        assertThatThrownBy(() -> service.register(command(TODAY.minusDays(6), null), TODAY))
                .isInstanceOf(RetroactiveRatePolicyException.class)
                .hasMessageContaining("3");

        verify(savePort, never()).save(any());
    }

    @Test @DisplayName("정산 존재 여부는 소급 구간(발효일~오늘)만 본다 — 미래분은 판정 대상이 아니다")
    void countsOnlyTheRetroactiveWindow() {
        when(countPort.countInPeriod(any(), any(), any(), any())).thenReturn(0L);

        service.register(command(TODAY.minusDays(6), null), TODAY);

        verify(countPort).countInPeriod(RateScope.SELLER, "77", TODAY.minusDays(6), TODAY);
    }

    @Test @DisplayName("거부 메시지는 정식 경로를 알려준다 — 운영자가 다음 행동을 알 수 있게")
    void rejectionPointsToAdjustment() {
        when(countPort.countInPeriod(any(), any(), any(), any())).thenReturn(1L);

        assertThatThrownBy(() -> service.register(command(TODAY.minusDays(1), null), TODAY))
                .hasMessageContaining("SettlementAdjustment");
    }
}
