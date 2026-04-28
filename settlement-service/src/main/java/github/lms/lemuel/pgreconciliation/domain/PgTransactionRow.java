package github.lms.lemuel.pgreconciliation.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * PG 정산파일의 한 줄을 나타내는 도메인 값 객체.
 *
 * <p>실 PG 들의 파일 포맷은 다양하지만 공통적으로 다음 정보를 포함한다:
 * <ul>
 *   <li>거래 ID (paymentKey / TID)</li>
 *   <li>매출 금액</li>
 *   <li>환불 금액 (당일 발생분)</li>
 *   <li>수수료</li>
 *   <li>정산 일자</li>
 * </ul>
 *
 * <p>각 PG 별 어댑터에서 자기 형식을 파싱해 이 공통 모델로 변환한다 — Anti-Corruption Layer.
 */
public record PgTransactionRow(
        String pgTransactionId,    // PG 측 거래 식별자 — 내부 payments.pg_transaction_id 와 매칭 키
        BigDecimal amount,          // PG 가 인식한 매출 금액
        BigDecimal refundedAmount,  // 해당 영업일에 환불된 금액 (없으면 0)
        BigDecimal fee,             // PG 수수료 (정보용 — 비교 대상 아님)
        LocalDate settledDate       // PG 가 정산할 영업일
) {
    public PgTransactionRow {
        Objects.requireNonNull(pgTransactionId, "pgTransactionId");
        Objects.requireNonNull(amount, "amount");
        if (refundedAmount == null) refundedAmount = BigDecimal.ZERO;
        if (fee == null) fee = BigDecimal.ZERO;
    }

    /**
     * 환불을 차감한 순매출 — 내부 결제와 비교할 때 사용.
     */
    public BigDecimal netAmount() {
        return amount.subtract(refundedAmount);
    }
}
