package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.RequestCorporateLoanUseCase.RequestCorporateLoanCommand;
import github.lms.lemuel.loan.application.port.out.LoadCompanyReputationPort;
import github.lms.lemuel.loan.application.port.out.LoadCorporateFinancialPort;
import github.lms.lemuel.loan.application.port.out.LoanMetricsPort;
import github.lms.lemuel.loan.application.port.out.SaveCorporateLoanPort;
import github.lms.lemuel.loan.domain.CompanyReputation;
import github.lms.lemuel.loan.domain.CorporateCreditPolicy;
import github.lms.lemuel.loan.domain.CorporateFinancials;
import github.lms.lemuel.loan.domain.CorporateLoan;
import github.lms.lemuel.loan.domain.CorporateLoanStatus;
import github.lms.lemuel.loan.domain.exception.CorporateLoanRejectedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestCorporateLoanServiceTest {

    @Mock LoadCorporateFinancialPort loadCorporateFinancialPort;
    @Mock LoadCompanyReputationPort loadCompanyReputationPort;
    @Mock SaveCorporateLoanPort saveCorporateLoanPort;
    @Mock LoanMetricsPort loanMetricsPort;

    private final CorporateCreditPolicy policy =
            new CorporateCreditPolicy(new BigDecimal("0.0002"), new BigDecimal("0.10"));

    // 고정 Clock — 신청 시각(createdAt) 스냅샷을 결정적으로 검증한다. KST(Asia/Seoul)로 09시 자정 경계도 고정.
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-07-16T00:30:00Z"), KST);

    private RequestCorporateLoanService service() {
        return new RequestCorporateLoanService(loadCorporateFinancialPort, loadCompanyReputationPort,
                saveCorporateLoanPort, policy, loanMetricsPort, fixedClock);
    }

    /** 신용점수 95(A), 자본총계 1,000,000 → 한도 100,000. */
    private CorporateFinancials strongCompany() {
        return new CorporateFinancials("005930", "삼성전자", "KOSPI", 2024,
                new BigDecimal("90"), new BigDecimal("20"), new BigDecimal("10"),
                new BigDecimal("1000000"), new BigDecimal("50000"));
    }

    /** 전 축 결측 + 평판 E → 점수 0 → 등급 E. */
    private CorporateFinancials brokenCompany() {
        return new CorporateFinancials("999999", "부실기업", "KOSDAQ", 2023,
                null, null, null, new BigDecimal("1000000"), new BigDecimal("-100"));
    }

    @Test
    void 한도이내_신청은_수수료를_산정해_REQUESTED로_저장한다() {
        when(loadCorporateFinancialPort.loadLatest("005930")).thenReturn(Optional.of(strongCompany()));
        when(loadCompanyReputationPort.findByStockCode("005930")).thenReturn(
                Optional.of(CompanyReputation.of("005930", 78, "B", "B", LocalDate.of(2026, 7, 1))));
        when(saveCorporateLoanPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 한도 100,000. 신청 100,000(경계, 이내), 30일, 등급 A → 수수료 100,000×0.0002×30×1.0 = 600
        CorporateLoan result = service().request(
                new RequestCorporateLoanCommand("005930", new BigDecimal("100000"), 30, 7L));

        ArgumentCaptor<CorporateLoan> captor = ArgumentCaptor.forClass(CorporateLoan.class);
        verify(saveCorporateLoanPort).save(captor.capture());
        CorporateLoan saved = captor.getValue();
        assertThat(saved.getStockCode()).isEqualTo("005930");
        assertThat(saved.getCorpName()).isEqualTo("삼성전자");
        assertThat(saved.getPrincipal()).isEqualByComparingTo("100000");
        assertThat(saved.getFee()).isEqualByComparingTo("600.00");
        assertThat(saved.getCreditScore()).isEqualTo(95);
        assertThat(saved.getCreditGrade()).isEqualTo("A");
        assertThat(saved.getStatus()).isEqualTo(CorporateLoanStatus.REQUESTED);
        assertThat(result.getStatus()).isEqualTo(CorporateLoanStatus.REQUESTED);
        verify(loanMetricsPort).corporateRequested();
        verify(loanMetricsPort, never()).corporateRejected();
    }

    @Test
    void 신청시각은_주입된_KST_Clock_기준으로_스냅샷된다() {
        when(loadCorporateFinancialPort.loadLatest("005930")).thenReturn(Optional.of(strongCompany()));
        when(loadCompanyReputationPort.findByStockCode("005930")).thenReturn(Optional.empty());
        when(saveCorporateLoanPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CorporateLoan result = service().request(
                new RequestCorporateLoanCommand("005930", new BigDecimal("100000"), 30, 7L));

        // Instant 2026-07-16T00:30Z → KST(+9) 09:30. 도메인 내부 now() 가 아니라 주입 Clock 이 출처여야 한다.
        assertThat(result.getCreatedAt())
                .isEqualTo(LocalDateTime.of(2026, 7, 16, 9, 30, 0));
    }

    @Test
    void 한도초과_신청은_422거절이고_저장하지_않는다() {
        when(loadCorporateFinancialPort.loadLatest("005930")).thenReturn(Optional.of(strongCompany()));
        when(loadCompanyReputationPort.findByStockCode("005930")).thenReturn(Optional.empty());

        // 한도 100,000 인데 100,001 신청 → 초과. 요청액/한도를 구조화 필드로 보존한다.
        assertThatThrownBy(() -> service().request(
                new RequestCorporateLoanCommand("005930", new BigDecimal("100001"), 30, 7L)))
                .isInstanceOfSatisfying(CorporateLoanRejectedException.class, ex -> {
                    assertThat(ex.getRequested()).isEqualByComparingTo("100001");
                    assertThat(ex.getLimit()).isEqualByComparingTo("100000");
                });

        verify(saveCorporateLoanPort, never()).save(any());
        verify(loanMetricsPort).corporateRejected();
        verify(loanMetricsPort, never()).corporateRequested();
    }

    @Test
    void E등급은_422거절이고_저장하지_않는다() {
        when(loadCorporateFinancialPort.loadLatest("999999")).thenReturn(Optional.of(brokenCompany()));
        when(loadCompanyReputationPort.findByStockCode("999999")).thenReturn(
                Optional.of(CompanyReputation.of("999999", 5, "E", "D", LocalDate.of(2026, 7, 1))));

        assertThatThrownBy(() -> service().request(
                new RequestCorporateLoanCommand("999999", new BigDecimal("1"), 30, 7L)))
                .isInstanceOf(CorporateLoanRejectedException.class)
                .hasMessageContaining("E");

        verify(saveCorporateLoanPort, never()).save(any());
        verify(loanMetricsPort).corporateRejected();
    }

    @Test
    void 재무자료가_없으면_422거절() {
        when(loadCorporateFinancialPort.loadLatest("000000")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().request(
                new RequestCorporateLoanCommand("000000", new BigDecimal("1000"), 30, 7L)))
                .isInstanceOf(CorporateLoanRejectedException.class);

        verify(saveCorporateLoanPort, never()).save(any());
        verify(loanMetricsPort).corporateRejected();
    }
}
