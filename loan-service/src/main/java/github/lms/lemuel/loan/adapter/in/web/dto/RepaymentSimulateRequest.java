package github.lms.lemuel.loan.adapter.in.web.dto;

import github.lms.lemuel.loan.application.port.in.SimulateRepaymentUseCase.SimulateRepaymentCommand;
import github.lms.lemuel.loan.domain.RepaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 상환 스케줄 시뮬레이션 요청.
 *
 * @param principal         대출 원금(원, 양수)
 * @param termMonths        기간(개월, 1~600)
 * @param annualRatePercent 연이자율(%, 0 이상) — 예: 5.5
 * @param method            상환방식(BULLET · EQUAL_PAYMENT · EQUAL_PRINCIPAL)
 */
public record RepaymentSimulateRequest(
        @NotNull @Positive BigDecimal principal,
        @Min(1) @Max(600) int termMonths,
        @NotNull @DecimalMin("0.0") BigDecimal annualRatePercent,
        @NotNull RepaymentMethod method) {

    public SimulateRepaymentCommand toCommand() {
        return new SimulateRepaymentCommand(principal, termMonths, annualRatePercent, method);
    }
}
