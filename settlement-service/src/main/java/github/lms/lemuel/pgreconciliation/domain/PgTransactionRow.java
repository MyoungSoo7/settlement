package github.lms.lemuel.pgreconciliation.domain;

import github.lms.lemuel.pgreconciliation.domain.exception.PgReconciliationInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * PG 정산파일의 한 줄을 나타내는 도메인 값 객체.
 *
 * <p>실 PG 들의 파일 포맷은 다양하지만 공통적으로 다음을 담는다:
 * 거래 ID(paymentKey/TID), 매출 금액, 당일 환불액, <b>공제 항목 분해</b>, <b>실입금</b>,
 * 정산 일자와 (PG 에 따라) 매입일·지급일. 각 PG 어댑터가 자기 형식을 이 공통 모델로
 * 변환한다 — Anti-Corruption Layer.
 *
 * <p><b>실입금 검증</b>: {@code 매출 − 환불 − 총공제 = 실입금} 이 성립해야 한다. PG 가 신고한
 * 실입금과 이 계산이 어긋나면 우리가 수수료 구조를 잘못 알고 있거나 PG 파일이 틀린 것이고,
 * 둘 다 자금 사고로 직결된다. 금액 대사만 하고 이 산술을 보지 않으면 "금액은 맞는데 돈이 덜
 * 들어온" 건이 그대로 통과한다.
 *
 * <p>실입금 컬럼이 없는 레거시 파일은 {@link #isDepositVerifiable()} 가 false 다 — 검증 불가와
 * 검증 실패를 구분해, 정보가 없다는 이유로 불일치를 만들어내지 않는다.
 */
public record PgTransactionRow(
        String pgTransactionId,     // PG 측 거래 식별자 — 내부 payments.pg_transaction_id 와 매칭 키
        BigDecimal amount,          // PG 가 인식한 매출 금액
        BigDecimal refundedAmount,  // 해당 영업일에 환불된 금액 (없으면 0)
        PgFeeBreakdown fees,        // 공제 항목 분해 (수수료·부가세·에스크로·이체)
        BigDecimal netDeposit,      // PG 가 신고한 실입금 (null = 미신고, 검증 불가)
        LocalDate settledDate,      // PG 가 정산할 영업일
        LocalDate purchaseDate,     // 매입일 (선택 — 카드사 매입 시점)
        LocalDate payoutDate        // 지급일 (선택 — 실제 입금 예정일)
) {

    /**
     * 실입금 차이 허용 오차 — 1원 미만은 PG 라운딩 관행으로 보고 불일치로 세지 않는다.
     * 금액 대사({@code PgReconciliationMatcher.ROUNDING_THRESHOLD})와 같은 기준이다.
     */
    public static final BigDecimal DEPOSIT_TOLERANCE = new BigDecimal("1.00");

    public PgTransactionRow {
        if (pgTransactionId == null) throw new PgReconciliationInvariantViolationException("pgTransactionId 는 필수입니다");
        if (amount == null) throw new PgReconciliationInvariantViolationException("amount 는 필수입니다");
        if (refundedAmount == null) refundedAmount = BigDecimal.ZERO;
        if (fees == null) fees = PgFeeBreakdown.none();
    }

    /**
     * 분해 이전 형식(단일 수수료 컬럼) 하위호환 생성자.
     *
     * <p>실입금이 없으므로 이렇게 만든 행은 실입금 검증 대상이 아니다.
     */
    public PgTransactionRow(String pgTransactionId, BigDecimal amount, BigDecimal refundedAmount,
                            BigDecimal fee, LocalDate settledDate) {
        this(pgTransactionId, amount, refundedAmount, PgFeeBreakdown.legacy(fee), null, settledDate, null, null);
    }

    public static PgTransactionRow of(String pgTransactionId, BigDecimal amount, BigDecimal refundedAmount,
                                      PgFeeBreakdown fees, BigDecimal netDeposit, LocalDate settledDate,
                                      LocalDate purchaseDate, LocalDate payoutDate) {
        return new PgTransactionRow(pgTransactionId, amount, refundedAmount, fees, netDeposit,
                settledDate, purchaseDate, payoutDate);
    }

    public static PgTransactionRow legacy(String pgTransactionId, BigDecimal amount,
                                          BigDecimal refundedAmount, BigDecimal fee, LocalDate settledDate) {
        return new PgTransactionRow(pgTransactionId, amount, refundedAmount, fee, settledDate);
    }

    /** 하위호환 접근자 — 총 공제액. 분해 이전 코드가 기대하던 단일 수수료 자리다. */
    public BigDecimal fee() {
        return fees.totalDeduction();
    }

    /** 환불을 차감한 순매출 — 내부 결제와 비교할 때 사용. */
    public BigDecimal netAmount() {
        return amount.subtract(refundedAmount);
    }

    /** 공제까지 반영해 우리가 계산한 실입금 — PG 신고값과 대조할 정답지. */
    public BigDecimal expectedNetDeposit() {
        return netAmount().subtract(fees.totalDeduction());
    }

    /** PG 가 실입금을 신고했는가 — false 면 산술 검증 자체가 불가능하다. */
    public boolean isDepositVerifiable() {
        return netDeposit != null;
    }

    /**
     * 신고 실입금 − 계산 실입금. 음수면 예상보다 <b>덜</b> 들어온 것(자금 부족),
     * 양수면 더 들어온 것이다. 미신고면 {@code null}.
     */
    public BigDecimal depositDifference() {
        return isDepositVerifiable() ? netDeposit.subtract(expectedNetDeposit()) : null;
    }

    /** 허용 오차를 넘는 실입금 불일치가 있는가. 미신고 건은 항상 false(검증 불가 ≠ 불일치). */
    public boolean hasDepositMismatch() {
        BigDecimal diff = depositDifference();
        return diff != null && diff.abs().compareTo(DEPOSIT_TOLERANCE) >= 0;
    }
}
