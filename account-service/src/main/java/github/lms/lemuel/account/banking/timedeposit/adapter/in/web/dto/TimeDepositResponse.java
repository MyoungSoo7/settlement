package github.lms.lemuel.account.banking.timedeposit.adapter.in.web.dto;

import github.lms.lemuel.account.banking.timedeposit.domain.Compounding;
import github.lms.lemuel.account.banking.timedeposit.domain.TimeDeposit;
import github.lms.lemuel.account.banking.timedeposit.domain.TimeDepositStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 정기예금 계좌 응답.
 *
 * <p>{@code settledInterest}·{@code closedOn}·{@code payoutAmount} 는 해지 전에는 {@code null} 이다 —
 * 이자를 해지 시점에 <b>한 번만</b> 확정하는 설계가 응답에 그대로 드러난다(0 으로 채워 "이자 0원"처럼
 * 보이게 하지 않는다).
 */
public record TimeDepositResponse(Long id,
                                  String depositorId,
                                  String productName,
                                  BigDecimal principal,
                                  BigDecimal annualRate,
                                  BigDecimal earlyTerminationRate,
                                  Compounding compounding,
                                  int termMonths,
                                  LocalDate openedOn,
                                  LocalDate maturityDate,
                                  TimeDepositStatus status,
                                  LocalDate closedOn,
                                  BigDecimal settledInterest,
                                  BigDecimal payoutAmount) {

    public static TimeDepositResponse from(TimeDeposit deposit) {
        return new TimeDepositResponse(
                deposit.getId(),
                deposit.getDepositorId(),
                deposit.getProductName(),
                deposit.getPrincipal(),
                deposit.getAnnualRate(),
                deposit.getEarlyTerminationRate(),
                deposit.getCompounding(),
                deposit.getTermMonths(),
                deposit.getOpenedOn(),
                deposit.getMaturityDate(),
                deposit.getStatus(),
                deposit.getClosedOn(),
                deposit.getSettledInterest(),
                deposit.getPayoutAmount());
    }
}
