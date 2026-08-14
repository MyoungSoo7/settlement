package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;

import java.math.BigDecimal;

/**
 * 중도해지 정산서(규정손해금 산정 결과) — 결정적 순수 계산.
 *
 * <h4>왜 잔액에 잔존가치가 이미 들어 있나</h4>
 * 리스 회차표의 잔액은 만기에 0 이 아니라 <b>잔존가치로 수렴</b>한다({@link LeaseSchedule}). 따라서 중도
 * 시점의 잔액은 "남은 리스료로 회수할 원금 + 아직 회수하지 않은 잔존가치"를 이미 합친 값이다. 여기에
 * 잔존가치를 또 더하면 <b>이중 청구</b>가 된다 — 실무에서 반복되는 오류라 계산을 한 곳에 가둔다.
 *
 * <h4>보증금</h4>
 * 보증금은 리스 원금에서 이미 빠져 있다(회수 대상이 아니다). 해지 시에는 반환 대상이므로 청구액에서
 * 상계한다. 상계 후 음수면 청구액 0 이고, 남는 금액은 별도 반환 채무({@link #refundDue()})다.
 *
 * @param settledInstallmentNo 정산 기준 회차(직전까지 납입 완료한 회차)
 * @param outstandingBalance   해지 시점 잔액(잔존가치 포함)
 * @param penaltyRatePercent   규정손해금률(%)
 * @param penalty              규정손해금(= 잔액 × 요율)
 * @param depositOffset        상계에 쓴 보증금
 * @param payable              최종 청구액(= max(0, 잔액 + 손해금 − 보증금))
 * @param refundDue            보증금이 남아 돌려줄 금액(청구액이 0 일 때만 발생)
 */
public record EarlyTerminationQuote(
        int settledInstallmentNo,
        BigDecimal outstandingBalance,
        BigDecimal penaltyRatePercent,
        BigDecimal penalty,
        BigDecimal depositOffset,
        BigDecimal payable,
        BigDecimal refundDue) {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    /** 규정손해금률 상한 — 이용자 보호 관점의 방어선. 초과 요율은 정책 오설정으로 본다. */
    public static final BigDecimal MAX_PENALTY_RATE_PERCENT = new BigDecimal("10");

    /**
     * 해지 정산을 산정한다.
     *
     * @param schedule           원 계약 스케줄
     * @param paidInstallments   납입 완료 회차 수(0 = 인도 후 미납, 만기 회차는 중도해지 대상이 아님)
     * @param penaltyRatePercent 규정손해금률(%) — 0 이상 {@value #MAX_PENALTY_RATE_PERCENT} 이하
     * @throws LoanInvariantViolationException 회차·요율이 범위를 벗어난 경우
     */
    public static EarlyTerminationQuote of(LeaseSchedule schedule, int paidInstallments,
                                           BigDecimal penaltyRatePercent) {
        if (schedule == null) throw new LoanInvariantViolationException("스케줄은 필수입니다");
        if (penaltyRatePercent == null || penaltyRatePercent.signum() < 0
                || penaltyRatePercent.compareTo(MAX_PENALTY_RATE_PERCENT) > 0) {
            throw new LoanInvariantViolationException(
                    "규정손해금률은 0~" + MAX_PENALTY_RATE_PERCENT + "% 여야 합니다: " + penaltyRatePercent);
        }
        if (paidInstallments < 0 || paidInstallments >= schedule.termMonths()) {
            throw new LoanInvariantViolationException(
                    "중도해지 기준 회차는 0~" + (schedule.termMonths() - 1) + " 여야 합니다(만기 회차는 만기 종료다): "
                            + paidInstallments);
        }

        RoundingPolicy rounding = schedule.roundingPolicy();
        BigDecimal balance = schedule.balanceAfter(paidInstallments);
        BigDecimal penalty = rounding.round(balance.multiply(penaltyRatePercent).divide(HUNDRED, java.math.MathContext.DECIMAL128));
        BigDecimal gross = balance.add(penalty);
        BigDecimal deposit = schedule.deposit();
        BigDecimal offset = gross.min(deposit);
        BigDecimal payable = rounding.round(gross.subtract(offset));
        BigDecimal refund = rounding.round(deposit.subtract(offset));

        return new EarlyTerminationQuote(paidInstallments, balance, penaltyRatePercent, penalty,
                rounding.round(offset), payable, refund);
    }
}
