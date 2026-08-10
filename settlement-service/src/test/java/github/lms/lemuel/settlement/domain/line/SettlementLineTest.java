package github.lms.lemuel.settlement.domain.line;

import github.lms.lemuel.settlement.domain.exception.SettlementInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 정산 라인 — 결제 1건을 주문 상품 단위로 분해한 것.
 *
 * <p><b>왜 필요한가</b>: 지금 정산은 결제 1건 = 정산 1건이라 "이 정산금에 어떤 상품이 얼마씩
 * 들어 있는지"를 답할 수 없다. 셀러·CS 문의 대응과 라인별 과세 구분이 불가능하고, 다판매자
 * 확장의 지반도 없다.
 *
 * <p><b>구성적 불변식</b>: {@code Σ(라인 상품금액) + 배송비 − 할인 == 결제금액}. 이 등식이
 * 깨진 라인 묶음은 아예 만들어지지 않는다 — 팩토리가 거부한다.
 */
class SettlementLineTest {

    private static SettlementLineDraft draft(long orderItemId, long productId, String amount, int qty) {
        return new SettlementLineDraft(orderItemId, productId, new BigDecimal(amount), qty);
    }

    @Test
    @DisplayName("배송비·할인이 라인 금액 비율로 배분된다")
    void allocatesShippingAndDiscount() {
        List<SettlementLine> lines = SettlementLine.allocate(
                List.of(draft(1L, 100L, "6000", 1), draft(2L, 200L, "4000", 2)),
                new BigDecimal("3000"),   // 배송비
                new BigDecimal("1000"),   // 할인
                new BigDecimal("12000")); // 결제금액 = 10000 + 3000 - 1000

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).getAllocatedShipping()).isEqualByComparingTo("1800"); // 6:4
        assertThat(lines.get(1).getAllocatedShipping()).isEqualByComparingTo("1200");
        assertThat(lines.get(0).getAllocatedDiscount()).isEqualByComparingTo("600");
        assertThat(lines.get(1).getAllocatedDiscount()).isEqualByComparingTo("400");
    }

    @Test
    @DisplayName("라인 정산액 = 상품금액 + 배분배송비 − 배분할인")
    void netLineAmount() {
        List<SettlementLine> lines = SettlementLine.allocate(
                List.of(draft(1L, 100L, "6000", 1), draft(2L, 200L, "4000", 2)),
                new BigDecimal("3000"), new BigDecimal("1000"), new BigDecimal("12000"));

        assertThat(lines.get(0).getNetLineAmount()).isEqualByComparingTo("7200"); // 6000+1800-600
        assertThat(lines.get(1).getNetLineAmount()).isEqualByComparingTo("4800"); // 4000+1200-400
    }

    @Test
    @DisplayName("라인 정산액 합 == 결제금액 — 구성적 균형")
    void netAmountsSumToPayment() {
        List<SettlementLine> lines = SettlementLine.allocate(
                List.of(draft(1L, 100L, "3333", 1), draft(2L, 200L, "3333", 1), draft(3L, 300L, "3334", 1)),
                new BigDecimal("2500"), new BigDecimal("777"), new BigDecimal("11723"));

        BigDecimal sum = lines.stream().map(SettlementLine::getNetLineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("11723");
    }

    @Test
    @DisplayName("결제금액이 구성과 어긋나면 생성 거부 — 깨진 묶음은 존재할 수 없다")
    void rejectsInconsistentPaymentAmount() {
        assertThatThrownBy(() -> SettlementLine.allocate(
                List.of(draft(1L, 100L, "10000", 1)),
                new BigDecimal("3000"), new BigDecimal("1000"),
                new BigDecimal("99999")))   // 실제 구성은 12000
                .isInstanceOf(SettlementInvariantViolationException.class)
                .hasMessageContaining("결제금액");
    }

    @Test
    @DisplayName("배송비·할인 0원인 단순 주문")
    void plainOrderWithoutShippingOrDiscount() {
        List<SettlementLine> lines = SettlementLine.allocate(
                List.of(draft(1L, 100L, "5000", 1)),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("5000"));

        assertThat(lines.getFirst().getNetLineAmount()).isEqualByComparingTo("5000");
        assertThat(lines.getFirst().getAllocatedShipping()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("라인은 주문 상품 식별자와 수량을 보존한다 — 사후 추적 근거")
    void keepsTraceableIdentity() {
        SettlementLine line = SettlementLine.allocate(
                List.of(draft(42L, 777L, "5000", 3)),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("5000")).getFirst();

        assertThat(line.getOrderItemId()).isEqualTo(42L);
        assertThat(line.getProductId()).isEqualTo(777L);
        assertThat(line.getQuantity()).isEqualTo(3);
        assertThat(line.getLineAmount()).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("라인이 없으면 거부 — 분해할 대상이 없다")
    void rejectsEmptyDrafts() {
        assertThatThrownBy(() -> SettlementLine.allocate(
                List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(SettlementInvariantViolationException.class);
    }

    @Test
    @DisplayName("음수 배송비는 거부 — 배송비 환급은 조정으로 표현한다")
    void rejectsNegativeShipping() {
        assertThatThrownBy(() -> SettlementLine.allocate(
                List.of(draft(1L, 100L, "5000", 1)),
                new BigDecimal("-1"), BigDecimal.ZERO, new BigDecimal("4999")))
                .isInstanceOf(SettlementInvariantViolationException.class);
    }

    @Test
    @DisplayName("할인이 상품금액을 넘어서면 거부 — 음수 정산 라인을 만들지 않는다")
    void rejectsDiscountExceedingGoods() {
        assertThatThrownBy(() -> SettlementLine.allocate(
                List.of(draft(1L, 100L, "5000", 1)),
                BigDecimal.ZERO, new BigDecimal("6000"), new BigDecimal("-1000")))
                .isInstanceOf(SettlementInvariantViolationException.class);
    }

    @Test
    @DisplayName("생성된 라인은 불변 — setter 없이 값만 읽힌다")
    void linesAreImmutable() {
        List<SettlementLine> lines = SettlementLine.allocate(
                List.of(draft(1L, 100L, "5000", 1)),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("5000"));

        assertThat(java.util.Arrays.stream(SettlementLine.class.getMethods())
                .filter(m -> m.getName().startsWith("set"))
                .filter(m -> m.getDeclaringClass() == SettlementLine.class)
                .toList()).isEmpty();
        assertThat(lines).isUnmodifiable();
    }
}
