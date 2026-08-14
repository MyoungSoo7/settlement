package github.lms.lemuel.settlement.domain.line;

import github.lms.lemuel.settlement.domain.exception.SettlementInvariantViolationException;

import java.math.BigDecimal;

/**
 * 라인 분해의 입력 — 주문 상품 한 줄에서 정산이 알아야 하는 최소 정보.
 *
 * <p>배송비·할인 배분 이전 상태다. 배분 결과를 담은 {@link SettlementLine} 과 분리해,
 * "주문에서 받은 것"과 "정산이 계산한 것"이 한 타입에 섞이지 않게 한다.
 *
 * @param orderItemId 주문 상품 식별자 — 사후 추적의 기준
 * @param productId   상품 식별자 (상품별 집계용)
 * @param lineAmount  상품 금액 (단가 × 수량, 주문 시점 스냅샷)
 * @param quantity    수량
 */
public record SettlementLineDraft(Long orderItemId, Long productId, BigDecimal lineAmount, int quantity) {

    public SettlementLineDraft {
        if (orderItemId == null) {
            throw new SettlementInvariantViolationException("orderItemId 는 필수입니다");
        }
        if (lineAmount == null) {
            throw new SettlementInvariantViolationException("lineAmount 는 필수입니다");
        }
        if (lineAmount.signum() < 0) {
            throw new SettlementInvariantViolationException("lineAmount 는 음수일 수 없습니다: " + lineAmount);
        }
        if (quantity <= 0) {
            throw new SettlementInvariantViolationException("quantity 는 양수여야 합니다: " + quantity);
        }
    }
}
