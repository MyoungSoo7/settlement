package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.payout.application.port.in.RequestPayoutUseCase;
import github.lms.lemuel.payout.domain.PayoutType;
import github.lms.lemuel.recovery.application.port.in.OffsetSellerRecoveryUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.RecordLoanDeductionPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.tax.application.WithholdingResolution;
import github.lms.lemuel.tax.application.port.in.ResolveSettlementWithholdingUseCase;
import github.lms.lemuel.tax.domain.TaxType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 상환 saga 의 종착점 — 차감 기록 + <b>지급 요청</b>.
 *
 * <p>지급 생성이 여기로 온 이유(L-3): 확정 시점에 금액을 확정하면 뒤늦게 도착하는 대출 차감을 반영할
 * 방법이 없다({@code Payout.amount} 는 final). 그 결과 loan 은 대출 잔액을 줄이는데 현금은 전액 나갔다.
 * loan 이 차감 0 이어도 항상 {@code repayment_applied} 를 발행하므로 모든 정산에 트리거가 온다.
 *
 * <p>차감 순서는 <b>원천징수 → 대출 상환차감 → 채권상계</b>다(정본: settlement-domain-rules).
 * 기준은 "못 뗐을 때 이월되는가" — 이월되는 쪽을 뒤로 미룬다.
 */
@ExtendWith(MockitoExtension.class)
class ApplyLoanDeductionServiceTest {

    @Mock RecordLoanDeductionPort recordLoanDeductionPort;
    @Mock LoadSettlementPort loadSettlementPort;
    @Mock ResolveSettlementWithholdingUseCase resolveSettlementWithholdingUseCase;
    @Mock OffsetSellerRecoveryUseCase offsetSellerRecoveryUseCase;
    @Mock RequestPayoutUseCase requestPayoutUseCase;

    private ApplyLoanDeductionService service() {
        return new ApplyLoanDeductionService(recordLoanDeductionPort, loadSettlementPort,
                resolveSettlementWithholdingUseCase, offsetSellerRecoveryUseCase, requestPayoutUseCase);
    }

    /** 결제 10,000 · 수수료 3% → net 9,700 · 홀드백 없음 → immediate 9,700. */
    private Settlement confirmedSettlement() {
        Settlement s = Settlement.createFromPayment(1L, 11L, new BigDecimal("10000"), LocalDate.now());
        s.assignId(100L);
        s.confirm();
        return s;
    }

    private BigDecimal capturedPayout() {
        ArgumentCaptor<BigDecimal> payout = ArgumentCaptor.forClass(BigDecimal.class);
        verify(requestPayoutUseCase).requestPayoutOfType(eq(100L), eq(7L), payout.capture(),
                eq(PayoutType.IMMEDIATE));
        return payout.getValue();
    }

    @Test
    @DisplayName("차감을 정산건별로 기록한다")
    void 차감을_정산건별로_기록한다() {
        when(loadSettlementPort.findById(100L)).thenReturn(Optional.of(confirmedSettlement()));
        lenient().when(resolveSettlementWithholdingUseCase.resolveForPayout(any(), any()))
                .thenReturn(WithholdingResolution.unregistered());
        lenient().when(offsetSellerRecoveryUseCase.offsetForConfirmedSettlement(any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);

        service().apply(100L, 7L, new BigDecimal("800800"));

        verify(recordLoanDeductionPort).record(100L, 7L, new BigDecimal("800800"));
    }

    @Test
    @DisplayName("지급액 = 즉시지급분 − 원천징수 − 대출차감 − 채권상계 (순서 고정)")
    void 지급액은_우선순위대로_차감된다() {
        Settlement s = confirmedSettlement();          // immediate 9,700
        when(loadSettlementPort.findById(100L)).thenReturn(Optional.of(s));
        when(resolveSettlementWithholdingUseCase.resolveForPayout(7L, s.getNetAmount()))
                .thenReturn(WithholdingResolution.of(TaxType.INDIVIDUAL, new BigDecimal("320")));
        when(offsetSellerRecoveryUseCase.offsetForConfirmedSettlement(eq(100L), eq(7L), any(), any()))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(2)).min(new BigDecimal("1000")));

        service().apply(100L, 7L, new BigDecimal("2000"));

        // 상계에 넘기는 가용액은 원천징수·대출차감을 뺀 잔여다 — 9,700 − 320 − 2,000 = 7,380
        ArgumentCaptor<BigDecimal> available = ArgumentCaptor.forClass(BigDecimal.class);
        verify(offsetSellerRecoveryUseCase).offsetForConfirmedSettlement(
                eq(100L), eq(7L), available.capture(), any());
        assertThat(available.getValue()).isEqualByComparingTo(new BigDecimal("7380"));
        assertThat(capturedPayout()).isEqualByComparingTo(new BigDecimal("6380"));  // 7,380 − 1,000
    }

    @Test
    @DisplayName("대출차감이 즉시지급 잔여를 넘으면 지급은 0 — 음수 송금 금지")
    void 대출차감이_잔여를_넘으면_지급은_0이다() {
        Settlement s = confirmedSettlement();
        when(loadSettlementPort.findById(100L)).thenReturn(Optional.of(s));
        when(resolveSettlementWithholdingUseCase.resolveForPayout(7L, s.getNetAmount()))
                .thenReturn(WithholdingResolution.of(TaxType.INDIVIDUAL, new BigDecimal("320")));
        when(offsetSellerRecoveryUseCase.offsetForConfirmedSettlement(any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);

        service().apply(100L, 7L, new BigDecimal("999999"));

        assertThat(capturedPayout()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("대출 없는 셀러(차감 0)도 지급이 만들어진다 — loan 이 0 도 발행하는 이유")
    void 차감이_0이어도_지급을_만든다() {
        Settlement s = confirmedSettlement();
        when(loadSettlementPort.findById(100L)).thenReturn(Optional.of(s));
        when(resolveSettlementWithholdingUseCase.resolveForPayout(7L, s.getNetAmount()))
                .thenReturn(WithholdingResolution.unregistered());   // 사업자 취급 → 원천징수 0
        when(offsetSellerRecoveryUseCase.offsetForConfirmedSettlement(any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);

        service().apply(100L, 7L, BigDecimal.ZERO);

        assertThat(capturedPayout()).isEqualByComparingTo(new BigDecimal("9700"));
    }
}
