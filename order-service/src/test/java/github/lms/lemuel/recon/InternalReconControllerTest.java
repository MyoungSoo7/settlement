package github.lms.lemuel.recon;

import github.lms.lemuel.recon.ReconQueryRepository.CompletedRefundRow;
import github.lms.lemuel.recon.ReconQueryRepository.ReconPaymentRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InternalReconController — order 자기 대사 합계 노출")
class InternalReconControllerTest {

    @Mock ReconQueryRepository repository;
    @InjectMocks InternalReconController controller;

    private final LocalDate d = LocalDate.of(2026, 7, 1);

    @Test
    @DisplayName("dailyTotals — 캡처/완료환불/반영환불 합계")
    void dailyTotals() {
        when(repository.sumCapturedPayments(d)).thenReturn(new BigDecimal("1000"));
        when(repository.sumCompletedRefunds(d)).thenReturn(new BigDecimal("100"));
        when(repository.sumRefundedAgainstCaptures(d)).thenReturn(new BigDecimal("50"));
        var r = controller.dailyTotals(d);
        assertThat(r.capturedPayments()).isEqualByComparingTo("1000");
        assertThat(r.completedRefunds()).isEqualByComparingTo("100");
        assertThat(r.refundedAgainstCaptures()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("periodTotals — 기간 합계 + 발행 건수")
    void periodTotals() {
        LocalDate to = d.plusDays(6);
        when(repository.sumCapturedPayments(d, to)).thenReturn(new BigDecimal("2000"));
        when(repository.sumCompletedRefunds(d, to)).thenReturn(new BigDecimal("200"));
        when(repository.countPaymentCapturedPublished(d, to)).thenReturn(5L);
        var r = controller.periodTotals(d, to);
        assertThat(r.capturedPayments()).isEqualByComparingTo("2000");
        assertThat(r.paymentCapturedPublishedCount()).isEqualTo(5L);
    }

    @Test
    @DisplayName("dailyCounts — 캡처/환불 건수")
    void dailyCounts() {
        when(repository.countCapturedPayments(d)).thenReturn(10L);
        when(repository.countCompletedRefunds(d)).thenReturn(3L);
        var r = controller.dailyCounts(d);
        assertThat(r.capturedCount()).isEqualTo(10L);
        assertThat(r.completedRefundsCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("refundsCompleted — limit 5000 상한")
    void refundsCompleted() {
        List<CompletedRefundRow> rows = List.of(new CompletedRefundRow(1L, 2L, new BigDecimal("10"), d));
        when(repository.listCompletedRefunds(eq(d), eq(d), eq(5000))).thenReturn(rows);
        var r = controller.refundsCompleted(d, d, 999999);
        assertThat(r).hasSize(1);
    }

    @Test
    @DisplayName("refundsCompletedSum — id 집합 COMPLETED 합계")
    void refundsCompletedSum() {
        when(repository.sumCompletedRefundsByIds(List.of(1L, 2L))).thenReturn(new BigDecimal("30"));
        var r = controller.refundsCompletedSum(new InternalReconController.RefundIdsRequest(List.of(1L, 2L)));
        assertThat(r.amount()).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("capturedPayments — PG 대사 행")
    void capturedPayments() {
        List<ReconPaymentRow> rows = List.of(new ReconPaymentRow(1L, "PG1", new BigDecimal("10"), BigDecimal.ZERO, d));
        when(repository.loadCapturedPaymentRows(d)).thenReturn(rows);
        assertThat(controller.capturedPayments(d)).hasSize(1);
    }
}
