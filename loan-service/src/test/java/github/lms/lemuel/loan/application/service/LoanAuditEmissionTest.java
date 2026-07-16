package github.lms.lemuel.loan.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.audit.application.AuditAspect;
import github.lms.lemuel.common.audit.application.AuditDetailSerializer;
import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.loan.application.port.in.ApplyRepaymentUseCase.ApplyRepaymentCommand;
import github.lms.lemuel.loan.application.port.in.RequestCorporateLoanUseCase.RequestCorporateLoanCommand;
import github.lms.lemuel.loan.application.port.in.RequestLoanUseCase.RequestLoanCommand;
import github.lms.lemuel.loan.application.port.out.AppendLedgerPort;
import github.lms.lemuel.loan.application.port.out.LoadCompanyReputationPort;
import github.lms.lemuel.loan.application.port.out.LoadCorporateFinancialPort;
import github.lms.lemuel.loan.application.port.out.LoadCorporateLoanPort;
import github.lms.lemuel.loan.application.port.out.LoadLoanPort;
import github.lms.lemuel.loan.application.port.out.LoadSellerReputationPort;
import github.lms.lemuel.loan.application.port.out.LoadSettlementViewPort;
import github.lms.lemuel.loan.application.port.out.LoanMetricsPort;
import github.lms.lemuel.loan.application.port.out.PublishCorporateLoanEventPort;
import github.lms.lemuel.loan.application.port.out.PublishLoanEventPort;
import github.lms.lemuel.loan.application.port.out.RecordRepaymentPort;
import github.lms.lemuel.loan.application.port.out.SaveCorporateLoanPort;
import github.lms.lemuel.loan.application.port.out.SaveLoanPort;
import github.lms.lemuel.loan.application.port.out.SaveSettlementViewPort;
import github.lms.lemuel.loan.domain.CorporateCreditPolicy;
import github.lms.lemuel.loan.domain.CorporateFinancials;
import github.lms.lemuel.loan.domain.CorporateLoan;
import github.lms.lemuel.loan.domain.CorporateLoanStatus;
import github.lms.lemuel.loan.domain.LoanAdvance;
import github.lms.lemuel.loan.domain.LoanStatus;
import github.lms.lemuel.loan.domain.exception.CorporateLoanRejectedException;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * loan 금전 유스케이스의 {@code @Auditable} 이 AuditAspect 를 통해 audit_logs 기록을 유발하는지 검증한다.
 *
 * <p>Spring 컨텍스트 없이 {@link AspectJProxyFactory}(proxyTargetClass) 로 실제 서비스 빈을 감싸
 * 프로덕션과 동일한 AOP 경로를 재현한다(감사 누락 회귀 방지). 실제 감사 저장은 {@link AuditLogger} 를
 * mock 으로 대체해 발생 여부만 확인한다.
 */
class LoanAuditEmissionTest {

    private final AuditLogger auditLogger = mock(AuditLogger.class);
    private final LoanMetricsPort metrics = mock(LoanMetricsPort.class);

    private final CreditPolicy creditPolicy = new CreditPolicy(new BigDecimal("0.80"), new BigDecimal("0.0002"),
            Map.of("A", BigDecimal.ONE, "B", BigDecimal.ONE, "C", new BigDecimal("0.85"),
                    "D", new BigDecimal("0.70"), "E", BigDecimal.ZERO));
    private final CorporateCreditPolicy corporatePolicy =
            new CorporateCreditPolicy(new BigDecimal("0.0002"), new BigDecimal("0.10"));

    /** 대상 서비스를 AuditAspect 로 감싼 프록시를 만든다(프로덕션과 동일 AOP 경로). */
    private <T> T proxied(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new AuditAspect(auditLogger, new AuditDetailSerializer(new ObjectMapper())));
        return factory.getProxy();
    }

    private CorporateFinancials strongCompany() {
        return new CorporateFinancials("005930", "삼성전자", "KOSPI", 2024,
                new BigDecimal("90"), new BigDecimal("20"), new BigDecimal("10"),
                new BigDecimal("1000000"), new BigDecimal("50000"));
    }

    @Test
    void 선정산_신청은_LOAN_ADVANCE_REQUESTED_감사를_남긴다() {
        LoadSettlementViewPort settlementView = mock(LoadSettlementViewPort.class);
        LoadSellerReputationPort reputation = mock(LoadSellerReputationPort.class);
        SaveLoanPort saveLoan = mock(SaveLoanPort.class);
        when(settlementView.sumUnpaidBySeller(7L)).thenReturn(new BigDecimal("1000000"));
        when(reputation.findGrade(7L)).thenReturn(Optional.empty());
        when(saveLoan.save(any())).thenReturn(
                LoanAdvance.reconstitute(11L, 7L, new BigDecimal("800000"), new BigDecimal("800"),
                        BigDecimal.ZERO, LoanStatus.REQUESTED));

        RequestLoanService service = proxied(
                new RequestLoanService(settlementView, reputation, saveLoan, creditPolicy, metrics));
        service.request(new RequestLoanCommand(7L, new BigDecimal("800000"), 5));

        verify(auditLogger).record(eq(AuditAction.LOAN_ADVANCE_REQUESTED), eq("LoanAdvance"), eq("11"), any());
    }

    @Test
    void 선정산_실행은_LOAN_ADVANCE_DISBURSED_감사를_남긴다() {
        LoadLoanPort loadLoan = mock(LoadLoanPort.class);
        SaveLoanPort saveLoan = mock(SaveLoanPort.class);
        LoadSettlementViewPort settlementView = mock(LoadSettlementViewPort.class);
        LoadSellerReputationPort reputation = mock(LoadSellerReputationPort.class);
        PublishLoanEventPort publish = mock(PublishLoanEventPort.class);
        AppendLedgerPort ledger = mock(AppendLedgerPort.class);
        when(loadLoan.load(1L)).thenReturn(LoanAdvance.reconstitute(1L, 7L, new BigDecimal("800000"),
                new BigDecimal("800"), BigDecimal.ZERO, LoanStatus.REQUESTED));
        when(settlementView.sumUnpaidBySellerForUpdate(7L)).thenReturn(new BigDecimal("1000000"));
        when(reputation.findGrade(7L)).thenReturn(Optional.empty());
        when(saveLoan.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DisburseLoanService service = proxied(new DisburseLoanService(loadLoan, saveLoan, settlementView,
                reputation, creditPolicy, publish, ledger, metrics));
        service.disburse(1L);

        verify(auditLogger).record(eq(AuditAction.LOAN_ADVANCE_DISBURSED), eq("LoanAdvance"), eq("1"), any());
    }

    @Test
    void 기업대출_신청_성공은_CORPORATE_LOAN_REQUESTED_감사를_남긴다() {
        LoadCorporateFinancialPort financials = mock(LoadCorporateFinancialPort.class);
        LoadCompanyReputationPort reputation = mock(LoadCompanyReputationPort.class);
        SaveCorporateLoanPort saveLoan = mock(SaveCorporateLoanPort.class);
        when(financials.loadLatest("005930")).thenReturn(Optional.of(strongCompany()));
        when(reputation.findByStockCode("005930")).thenReturn(Optional.empty());
        when(saveLoan.save(any())).thenReturn(CorporateLoan.reconstitute(77L, "005930", "삼성전자",
                new BigDecimal("100000"), new BigDecimal("600"), BigDecimal.ZERO, 30, 95, "A",
                CorporateLoanStatus.REQUESTED, null));

        RequestCorporateLoanService service = proxied(new RequestCorporateLoanService(
                financials, reputation, saveLoan, corporatePolicy, metrics, java.time.Clock.systemUTC()));
        service.request(new RequestCorporateLoanCommand("005930", new BigDecimal("100000"), 30, 7L));

        verify(auditLogger).record(eq(AuditAction.CORPORATE_LOAN_REQUESTED), eq("CorporateLoan"), eq("77"), any());
    }

    @Test
    void 기업대출_신용거절은_CORPORATE_LOAN_REJECTED_감사를_남긴다() {
        LoadCorporateFinancialPort financials = mock(LoadCorporateFinancialPort.class);
        LoadCompanyReputationPort reputation = mock(LoadCompanyReputationPort.class);
        SaveCorporateLoanPort saveLoan = mock(SaveCorporateLoanPort.class);
        when(financials.loadLatest("000000")).thenReturn(Optional.empty()); // 재무자료 없음 → 거절

        RequestCorporateLoanService service = proxied(new RequestCorporateLoanService(
                financials, reputation, saveLoan, corporatePolicy, metrics, java.time.Clock.systemUTC()));

        assertThatThrownBy(() -> service.request(
                new RequestCorporateLoanCommand("000000", new BigDecimal("1000"), 30, 7L)))
                .isInstanceOf(CorporateLoanRejectedException.class);

        verify(auditLogger).record(eq(AuditAction.CORPORATE_LOAN_REJECTED), eq("CorporateLoan"), any(), any());
    }

    @Test
    void 기업대출_실행은_CORPORATE_LOAN_DISBURSED_감사를_남긴다() {
        LoadCorporateLoanPort loadLoan = mock(LoadCorporateLoanPort.class);
        SaveCorporateLoanPort saveLoan = mock(SaveCorporateLoanPort.class);
        AppendLedgerPort ledger = mock(AppendLedgerPort.class);
        PublishCorporateLoanEventPort publish = mock(PublishCorporateLoanEventPort.class);
        when(loadLoan.findByIdForUpdate(5001L)).thenReturn(Optional.of(CorporateLoan.reconstitute(
                5001L, "005930", "삼성전자", new BigDecimal("1000000"), new BigDecimal("6000"),
                BigDecimal.ZERO, 30, 82, "A", CorporateLoanStatus.REQUESTED, null)));
        when(saveLoan.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DisburseCorporateLoanService service = proxied(new DisburseCorporateLoanService(
                loadLoan, saveLoan, ledger, publish, metrics));
        service.disburse(5001L);

        verify(auditLogger).record(eq(AuditAction.CORPORATE_LOAN_DISBURSED), eq("CorporateLoan"), eq("5001"), any());
    }

    @Test
    void 상환_적용은_LOAN_REPAYMENT_APPLIED_감사를_남긴다() {
        LoadLoanPort loadLoan = mock(LoadLoanPort.class);
        SaveLoanPort saveLoan = mock(SaveLoanPort.class);
        RecordRepaymentPort recordRepayment = mock(RecordRepaymentPort.class);
        SaveSettlementViewPort saveSettlementView = mock(SaveSettlementViewPort.class);
        PublishLoanEventPort publish = mock(PublishLoanEventPort.class);
        AppendLedgerPort ledger = mock(AppendLedgerPort.class);
        when(recordRepayment.existsForSettlement(100L)).thenReturn(false);
        when(loadLoan.findDisbursedBySellerForUpdate(7L)).thenReturn(List.of());

        ApplyRepaymentService service = proxied(new ApplyRepaymentService(loadLoan, saveLoan, recordRepayment,
                saveSettlementView, publish, ledger, metrics));
        service.apply(new ApplyRepaymentCommand(100L, 7L, new BigDecimal("500000")));

        verify(auditLogger).record(eq(AuditAction.LOAN_REPAYMENT_APPLIED), eq("LoanRepayment"), eq("100"), any());
    }
}
