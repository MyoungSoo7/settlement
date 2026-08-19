package github.lms.lemuel.report.domain;

import java.math.BigDecimal;

/**
 * 구성비가 붙은 매출 구간 — {@link SalesSlice} 에 "전체 중 몇 %인가"를 더한 것.
 *
 * <p>{@code sharePercent} 는 <b>0~100 스케일</b>이다(0.75 가 아니라 75.00). 화면이 다시
 * 100 을 곱하는 일이 없도록 서버가 표시 단위로 확정한다.
 */
public record SalesShare(String label, long transactionCount, BigDecimal gmv,
                         BigDecimal refundedAmount, BigDecimal commissionAmount,
                         BigDecimal netSettlement, BigDecimal sharePercent) {

    static SalesShare of(SalesSlice slice, BigDecimal sharePercent) {
        return new SalesShare(slice.label(), slice.transactionCount(), slice.gmv(),
                slice.refundedAmount(), slice.commissionAmount(), slice.netSettlement(),
                sharePercent);
    }
}
