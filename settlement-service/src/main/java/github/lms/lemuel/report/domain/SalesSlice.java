package github.lms.lemuel.report.domain;

import java.math.BigDecimal;

/**
 * 한 구간의 매출 집계 — 결제수단·셀러 등급·정산 상태·셀러·상품 중 <b>어느 축이든</b> 한 칸.
 *
 * <p>축마다 별도 타입을 두지 않는 이유: 축이 달라도 화면이 그리는 것은 늘 "라벨 + 금액 + 건수"다.
 * 축을 타입으로 나누면 같은 계산(구성비·정렬)이 축 수만큼 복제된다.
 *
 * <p>{@code label} 이 비어 올 수 있다 — {@code payment_method} 가 NULL 인 옛 결제가 실제로 있다.
 * 그걸 그대로 화면에 흘리면 빈 칸이 생기므로 여기서 {@code UNKNOWN} 으로 고정한다.
 */
public record SalesSlice(String label, long transactionCount, BigDecimal gmv,
                         BigDecimal refundedAmount, BigDecimal commissionAmount,
                         BigDecimal netSettlement) {

    /** 라벨을 알 수 없는 구간의 표시명. */
    public static final String UNKNOWN_LABEL = "UNKNOWN";

    public SalesSlice {
        label = (label == null || label.isBlank()) ? UNKNOWN_LABEL : label;
        gmv = nz(gmv);
        refundedAmount = nz(refundedAmount);
        commissionAmount = nz(commissionAmount);
        netSettlement = nz(netSettlement);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
