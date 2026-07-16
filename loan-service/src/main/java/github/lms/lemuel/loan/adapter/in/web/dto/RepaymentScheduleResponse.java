package github.lms.lemuel.loan.adapter.in.web.dto;

import github.lms.lemuel.loan.domain.RepaymentInstallment;
import github.lms.lemuel.loan.domain.RepaymentMethod;
import github.lms.lemuel.loan.domain.RepaymentSchedule;

import java.math.BigDecimal;
import java.util.List;

/**
 * 상환 스케줄 시뮬레이션 응답. 금액은 원(KRW) 단위 정수 {@link BigDecimal} 로 직렬화한다.
 *
 * @param principal      원금
 * @param termMonths     기간(개월)
 * @param annualRatePercent 연이자율(%)
 * @param method         상환방식 코드
 * @param methodLabel    상환방식 한글명
 * @param totalPrincipal 원금 합계
 * @param totalInterest  이자 합계
 * @param totalPayment   총 납입액 합계
 * @param installments   회차별 상환 내역
 */
public record RepaymentScheduleResponse(
        BigDecimal principal,
        int termMonths,
        BigDecimal annualRatePercent,
        RepaymentMethod method,
        String methodLabel,
        BigDecimal totalPrincipal,
        BigDecimal totalInterest,
        BigDecimal totalPayment,
        List<Installment> installments) {

    /**
     * @param installmentNo    회차
     * @param principalPortion 납입 원금
     * @param interest         이자
     * @param payment          납입액
     * @param remainingBalance 상환 후 잔액
     */
    public record Installment(
            int installmentNo,
            BigDecimal principalPortion,
            BigDecimal interest,
            BigDecimal payment,
            BigDecimal remainingBalance) {

        static Installment from(RepaymentInstallment it) {
            return new Installment(it.installmentNo(), it.principalPortion(), it.interest(),
                    it.payment(), it.remainingBalance());
        }
    }

    public static RepaymentScheduleResponse from(RepaymentSchedule schedule) {
        List<Installment> rows = schedule.installments().stream()
                .map(Installment::from)
                .toList();
        return new RepaymentScheduleResponse(
                schedule.principal(),
                schedule.termMonths(),
                schedule.annualRatePercent(),
                schedule.method(),
                schedule.method().label(),
                schedule.totalPrincipal(),
                schedule.totalInterest(),
                schedule.totalPayment(),
                rows);
    }
}
