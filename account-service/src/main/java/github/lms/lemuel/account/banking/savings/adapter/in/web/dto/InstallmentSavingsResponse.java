package github.lms.lemuel.account.banking.savings.adapter.in.web.dto;

import github.lms.lemuel.account.banking.savings.domain.InstallmentSavings;
import github.lms.lemuel.account.banking.savings.domain.SavingsStatus;
import github.lms.lemuel.account.banking.savings.domain.SavingsType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 적금 계약 응답.
 *
 * <p>{@code totalPaidAmount} 는 도메인 파생값을 그대로 실어 보낸다 — 클라이언트가 회차를 다시
 * 합산하면 페이징·필터가 붙는 순간 서버와 값이 갈라진다.
 */
public record InstallmentSavingsResponse(Long id,
                                         String depositorId,
                                         String productName,
                                         SavingsType savingsType,
                                         BigDecimal monthlyAmount,
                                         BigDecimal paymentLimit,
                                         BigDecimal annualRate,
                                         BigDecimal earlyTerminationRate,
                                         int termMonths,
                                         LocalDate openedOn,
                                         LocalDate maturityDate,
                                         SavingsStatus status,
                                         LocalDate closedOn,
                                         BigDecimal totalPaidAmount,
                                         BigDecimal settledInterest,
                                         BigDecimal payoutAmount,
                                         List<SavingsInstallmentResponse> installments) {

    public static InstallmentSavingsResponse from(InstallmentSavings savings) {
        return new InstallmentSavingsResponse(
                savings.getId(),
                savings.getDepositorId(),
                savings.getProductName(),
                savings.getSavingsType(),
                savings.getMonthlyAmount(),
                savings.getPaymentLimit(),
                savings.getAnnualRate(),
                savings.getEarlyTerminationRate(),
                savings.getTermMonths(),
                savings.getOpenedOn(),
                savings.getMaturityDate(),
                savings.getStatus(),
                savings.getClosedOn(),
                savings.totalPaidAmount(),
                savings.getSettledInterest(),
                savings.getPayoutAmount(),
                savings.getInstallments().stream().map(SavingsInstallmentResponse::from).toList());
    }
}
