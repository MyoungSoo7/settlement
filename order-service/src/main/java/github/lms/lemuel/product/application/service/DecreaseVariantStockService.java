package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.DecreaseVariantStockUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.application.port.out.SaveProductVariantPort;
import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.ProductVariantStatus;
import github.lms.lemuel.product.domain.exception.InsufficientStockException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 옵션(SKU) 재고 차감 — 원자적 조건부 UPDATE 기반 동시성 제어.
 *
 * <p>흐름: 단 한 번의 {@code UPDATE ... SET stock = stock - q WHERE id = ? AND stock >= q AND status <> DISCONTINUED}
 * 로 "재고 검증 + 차감 + 매진 전이" 를 DB row 락 안에서 원자적으로 처리한다.
 * <ul>
 *   <li>영향 행 1 → 차감 성공. 차감된 최종 상태를 다시 읽어 반환.</li>
 *   <li>영향 행 0 → 재고 부족·단종·미존재 중 하나. 원인을 분류해 적절한 예외로 전환.</li>
 * </ul>
 *
 * <p>낙관적 락(@Version)+재시도 방식과 달리, 선착순/핫딜로 같은 SKU 에 차감이 폭주해도
 * 락 대기·충돌 재시도·재시도 한계 실패가 발생하지 않으며 초과판매도 방지된다. 따라서 별도의
 * 백오프 재시도 루프가 필요 없다.
 *
 * <p>운영 메트릭:
 * <ul>
 *   <li>{@code variant.stock.decrease.success} — 차감 성공 누적</li>
 *   <li>{@code variant.stock.decrease.rejected} — 재고 부족·단종 등으로 차감 거절된 누적</li>
 * </ul>
 */
@Service
public class DecreaseVariantStockService implements DecreaseVariantStockUseCase {

    private final LoadProductVariantPort loadPort;
    private final SaveProductVariantPort savePort;
    private final TransactionTemplate transactionTemplate;
    private final Counter successCounter;
    private final Counter rejectedCounter;

    public DecreaseVariantStockService(LoadProductVariantPort loadPort,
                                       SaveProductVariantPort savePort,
                                       TransactionTemplate transactionTemplate,
                                       MeterRegistry meterRegistry) {
        this.loadPort = loadPort;
        this.savePort = savePort;
        this.transactionTemplate = transactionTemplate;
        this.successCounter = Counter.builder("variant.stock.decrease.success")
                .description("Variant 재고 차감 성공 누적")
                .register(meterRegistry);
        this.rejectedCounter = Counter.builder("variant.stock.decrease.rejected")
                .description("재고 부족·단종 등으로 차감 거절된 누적")
                .register(meterRegistry);
    }

    @Override
    public ProductVariant decrease(Long variantId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("차감 수량은 양수여야 합니다");
        }
        ProductVariant result = transactionTemplate.execute(status -> {
            int affected = savePort.decreaseStockIfAvailable(variantId, quantity);
            if (affected == 0) {
                throw classifyFailure(variantId, quantity);
            }
            return loadPort.loadById(variantId).orElseThrow(() -> new IllegalStateException(
                    "재고 차감 후 variant 사라짐 (id=" + variantId + ")"));
        });
        successCounter.increment();
        return result;
    }

    /**
     * 원자적 차감 실패(영향 행 0) 원인 분류. 경합이 아니라 '차감 불가' 상태이므로 재시도 대상이 아니다.
     */
    private RuntimeException classifyFailure(Long variantId, int quantity) {
        rejectedCounter.increment();
        ProductVariant current = loadPort.loadById(variantId).orElse(null);
        if (current == null) {
            return new IllegalArgumentException("ProductVariant not found: " + variantId);
        }
        if (current.getStatus() == ProductVariantStatus.DISCONTINUED) {
            return new IllegalStateException("단종된 SKU 는 차감할 수 없습니다: " + current.getSku());
        }
        return new InsufficientStockException(
                "재고 부족: sku=" + current.getSku() + ", 요청=" + quantity
                        + ", 가용=" + current.getStockQuantity());
    }

    /**
     * 외부에서 트랜잭션을 가지고 들어온 환경 (e.g. 결제 트랜잭션 안) 에서 사용할 진입점.
     * 내부에서 {@code REQUIRES_NEW} 로 새 트랜잭션을 열어 차감을 독립 커밋한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProductVariant decreaseInNewTransaction(Long variantId, int quantity) {
        return decrease(variantId, quantity);
    }
}
