package github.lms.lemuel.loan.adapter.in.web.dto;

import github.lms.lemuel.loan.domain.AssetFinanceType;
import github.lms.lemuel.loan.domain.EarlyTerminationQuote;
import github.lms.lemuel.loan.domain.LeaseContract;
import github.lms.lemuel.loan.domain.LeaseInstallment;
import github.lms.lemuel.loan.domain.LeaseSchedule;
import github.lms.lemuel.loan.domain.LeaseStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 리스·할부 API 요청·응답 DTO 모음.
 *
 * <p>요청에 <b>차주 식별자가 없다</b> — JWT 주체에서 파생하기 때문이다(IDOR 방지). 금액은 전부
 * {@link BigDecimal} 이고 원 단위 정수만 받는다(소수 입력은 도메인이 조용히 보정하지 않고 거부).
 */
public final class LeaseDtos {

    private LeaseDtos() {
    }

    /** 리스·할부 신청. */
    public record LeaseApplyRequest(
            @NotNull(message = "상품 종류는 필수입니다") AssetFinanceType financeType,
            @NotBlank(message = "리스 물건 표시는 필수입니다") String assetDescription,
            @NotNull @Positive @Digits(integer = 17, fraction = 0, message = "취득원가는 원 단위 정수여야 합니다")
            BigDecimal acquisitionCost,
            @Digits(integer = 17, fraction = 0, message = "선수금은 원 단위 정수여야 합니다") BigDecimal downPayment,
            @Digits(integer = 17, fraction = 0, message = "보증금은 원 단위 정수여야 합니다") BigDecimal deposit,
            @Digits(integer = 17, fraction = 0, message = "잔존가치는 원 단위 정수여야 합니다") BigDecimal residualValue,
            @Min(value = 1, message = "리스 기간은 1개월 이상이어야 합니다")
            @Max(value = 240, message = "리스 기간은 240개월을 넘을 수 없습니다") int termMonths,
            @NotNull @DecimalMin(value = "0.0", message = "연이율은 음수일 수 없습니다") BigDecimal annualRatePercent) {

        public BigDecimal downPaymentOrZero() {
            return downPayment == null ? BigDecimal.ZERO : downPayment;
        }

        public BigDecimal depositOrZero() {
            return deposit == null ? BigDecimal.ZERO : deposit;
        }

        public BigDecimal residualValueOrZero() {
            return residualValue == null ? BigDecimal.ZERO : residualValue;
        }
    }

    /** 계약 요약 — 회차표는 별도 엔드포인트에서 준다(응답 크기 방어). */
    public record LeaseContractResponse(
            Long id,
            AssetFinanceType financeType,
            String financeTypeLabel,
            String assetDescription,
            String borrowerName,
            LeaseStatus status,
            int termMonths,
            int paidInstallments,
            BigDecimal acquisitionCost,
            BigDecimal financedAmount,
            BigDecimal residualValue,
            BigDecimal monthlyRental,
            BigDecimal outstandingBalance,
            OffsetDateTime appliedAt,
            OffsetDateTime activatedAt,
            OffsetDateTime closedAt) {

        public static LeaseContractResponse from(LeaseContract contract) {
            LeaseSchedule schedule = contract.getSchedule();
            return new LeaseContractResponse(
                    contract.getId(), contract.getType(), contract.getType().label(),
                    contract.getAssetDescription(), contract.getBorrower().name(), contract.getStatus(),
                    schedule.termMonths(), contract.getPaidInstallments(), schedule.acquisitionCost(),
                    schedule.financedAmount(), schedule.residualValue(), schedule.monthlyRental(),
                    contract.outstandingBalance(), contract.getAppliedAt(), contract.getActivatedAt(),
                    contract.getClosedAt());
        }
    }

    /** 회차표 — 만기 잔액이 0 이 아니라 잔존가치로 수렴한다는 점이 대출 상환표와 다르다. */
    public record LeaseScheduleResponse(
            AssetFinanceType financeType,
            BigDecimal financedAmount,
            BigDecimal residualValue,
            BigDecimal monthlyRental,
            BigDecimal totalRental,
            BigDecimal totalInterest,
            List<InstallmentRow> installments) {

        public static LeaseScheduleResponse from(LeaseSchedule schedule) {
            return new LeaseScheduleResponse(schedule.type(), schedule.financedAmount(),
                    schedule.residualValue(), schedule.monthlyRental(), schedule.totalRental(),
                    schedule.totalInterest(), schedule.installments().stream().map(InstallmentRow::from).toList());
        }

        public record InstallmentRow(int installmentNo, BigDecimal rental, BigDecimal principalPortion,
                                     BigDecimal interest, BigDecimal remainingBalance) {

            static InstallmentRow from(LeaseInstallment installment) {
                return new InstallmentRow(installment.installmentNo(), installment.rental(),
                        installment.principalPortion(), installment.interest(), installment.remainingBalance());
            }
        }
    }

    /** 중도해지 정산서. */
    public record EarlyTerminationResponse(
            int settledInstallmentNo,
            BigDecimal outstandingBalance,
            BigDecimal penaltyRatePercent,
            BigDecimal penalty,
            BigDecimal depositOffset,
            BigDecimal payable,
            BigDecimal refundDue) {

        public static EarlyTerminationResponse from(EarlyTerminationQuote quote) {
            return new EarlyTerminationResponse(quote.settledInstallmentNo(), quote.outstandingBalance(),
                    quote.penaltyRatePercent(), quote.penalty(), quote.depositOffset(), quote.payable(),
                    quote.refundDue());
        }
    }
}
