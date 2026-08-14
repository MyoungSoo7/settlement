package github.lms.lemuel.settlement.domain.line;

import github.lms.lemuel.settlement.domain.exception.SettlementInvariantViolationException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 정산 라인 — 결제 1건을 주문 상품 단위로 분해한 결과.
 *
 * <p><b>왜 필요한가</b>: 지금 정산은 결제 1건 = 정산 1건이라 "이 정산금에 어떤 상품이 얼마씩
 * 들어 있는지"를 답할 수 없다. 셀러·CS 문의 대응, 라인별 과세 구분, 다판매자 확장이 전부
 * 이 분해 위에 선다.
 *
 * <p><b>구성적 균형</b>: {@code Σ(라인 상품금액) + 배송비 − 할인 == 결제금액}. 이 등식이 깨진
 * 라인 묶음은 <b>만들어지지 않는다</b> — {@link #allocate} 가 유일 생성 경로이고 거기서 거부한다.
 * 사후 검증(저장 후 대사)에 맡기면 이미 틀린 데이터가 원장까지 흘러간 뒤다.
 *
 * <p>배송비·할인 배분은 {@link AmountAllocator}(최대잔여법)에 위임한다 — 배분 합이 원본과
 * 정확히 일치하므로, 라인 정산액의 합도 자동으로 결제금액과 맞는다.
 *
 * <p>불변 객체다: 모든 필드가 final 이고 setter 가 없다. 배분이 끝난 라인은 사실의 기록이지
 * 편집 대상이 아니다(수정이 필요하면 정산 조정으로 표현한다).
 */
public final class SettlementLine {

    private final Long orderItemId;
    private final Long productId;
    private final BigDecimal lineAmount;
    private final int quantity;
    private final BigDecimal allocatedShipping;
    private final BigDecimal allocatedDiscount;

    private SettlementLine(Long orderItemId, Long productId, BigDecimal lineAmount, int quantity,
                           BigDecimal allocatedShipping, BigDecimal allocatedDiscount) {
        this.orderItemId = orderItemId;
        this.productId = productId;
        this.lineAmount = lineAmount;
        this.quantity = quantity;
        this.allocatedShipping = allocatedShipping;
        this.allocatedDiscount = allocatedDiscount;
    }

    /**
     * 주문 상품 목록을 정산 라인으로 분해한다 — 유일 생성 경로.
     *
     * @param drafts         주문 상품 줄 (비어 있을 수 없다)
     * @param shippingFee    주문 단위 배송비 (0 이상)
     * @param discountAmount 주문 단위 할인 (0 이상, 상품금액 합을 넘을 수 없다)
     * @param paymentAmount  실제 결제금액 — 구성과 일치해야 한다
     * @throws SettlementInvariantViolationException 사전조건·구성적 균형 위반
     */
    public static List<SettlementLine> allocate(List<SettlementLineDraft> drafts,
                                                BigDecimal shippingFee,
                                                BigDecimal discountAmount,
                                                BigDecimal paymentAmount) {
        if (drafts == null || drafts.isEmpty()) {
            throw new SettlementInvariantViolationException("분해할 주문 상품이 없습니다");
        }
        BigDecimal shipping = requireNonNegative(shippingFee, "배송비");
        BigDecimal discount = requireNonNegative(discountAmount, "할인금액");
        if (paymentAmount == null) {
            throw new SettlementInvariantViolationException("결제금액은 필수입니다");
        }

        BigDecimal goodsTotal = drafts.stream()
                .map(SettlementLineDraft::lineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 할인이 상품금액을 넘으면 라인 정산액이 음수가 된다 — 그건 정산이 아니라 환급이므로
        // 조정(SettlementAdjustment)으로 표현해야 한다.
        if (discount.compareTo(goodsTotal) > 0) {
            throw new SettlementInvariantViolationException(
                    "할인이 상품금액 합을 초과합니다: discount=" + discount + ", goods=" + goodsTotal);
        }

        BigDecimal composed = goodsTotal.add(shipping).subtract(discount);
        if (composed.compareTo(paymentAmount) != 0) {
            throw new SettlementInvariantViolationException(
                    "결제금액이 라인 구성과 일치하지 않습니다: 구성=" + composed
                            + " (상품 " + goodsTotal + " + 배송 " + shipping + " − 할인 " + discount + ")"
                            + ", 결제금액=" + paymentAmount);
        }

        List<BigDecimal> weights = drafts.stream().map(SettlementLineDraft::lineAmount).toList();
        List<BigDecimal> shippingShares = AmountAllocator.allocate(shipping, weights);
        List<BigDecimal> discountShares = AmountAllocator.allocate(discount, weights);

        List<SettlementLine> lines = new ArrayList<>(drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            SettlementLineDraft d = drafts.get(i);
            lines.add(new SettlementLine(d.orderItemId(), d.productId(), d.lineAmount(), d.quantity(),
                    shippingShares.get(i), discountShares.get(i)));
        }
        return List.copyOf(lines);
    }

    private static BigDecimal requireNonNegative(BigDecimal value, String field) {
        if (value == null) {
            throw new SettlementInvariantViolationException(field + " 은 필수입니다");
        }
        if (value.signum() < 0) {
            throw new SettlementInvariantViolationException(field + " 은 음수일 수 없습니다: " + value);
        }
        return value;
    }

    public Long getOrderItemId() { return orderItemId; }
    public Long getProductId() { return productId; }
    public BigDecimal getLineAmount() { return lineAmount; }
    public int getQuantity() { return quantity; }
    public BigDecimal getAllocatedShipping() { return allocatedShipping; }
    public BigDecimal getAllocatedDiscount() { return allocatedDiscount; }

    /** 이 라인에 귀속되는 정산 대상 금액 — 상품금액 + 배분 배송비 − 배분 할인. */
    public BigDecimal getNetLineAmount() {
        return lineAmount.add(allocatedShipping).subtract(allocatedDiscount);
    }
}
