package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.DecreaseProductStockUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.SaveProductPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductStatus;
import github.lms.lemuel.common.opssignal.NoOpOpsSignalPublisher;
import github.lms.lemuel.common.opssignal.OpsSignalCategory;
import github.lms.lemuel.common.opssignal.OpsSignalPort;
import github.lms.lemuel.product.domain.exception.InsufficientStockException;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

/**
 * 옵션 없는 일반 상품 재고 차감 — 원자적 조건부 UPDATE 기반 동시성 제어.
 *
 * <p>{@link DecreaseVariantStockService}(옵션/SKU 재고) 와 동일한 전략을 일반 상품
 * {@code products.stock_quantity} 에 적용한다: 단 한 번의
 * {@code UPDATE ... SET stock = stock - q WHERE id = ? AND stock >= q AND status <> DISCONTINUED}
 * 로 "재고 검증 + 차감 + 매진 전이" 를 DB row 락 안에서 원자적으로 처리해, 락 대기·재시도 없이
 * 초과판매를 방지한다.
 *
 * <p>운영 메트릭:
 * <ul>
 *   <li>{@code product.stock.decrease.success} — 차감 성공 누적</li>
 *   <li>{@code product.stock.decrease.rejected} — 재고 부족·단종 등으로 거절된 누적</li>
 * </ul>
 */
@Service
public class DecreaseProductStockService implements DecreaseProductStockUseCase {

    private final LoadProductPort loadPort;
    private final SaveProductPort savePort;
    private final TransactionTemplate transactionTemplate;
    private final Counter successCounter;
    private final Counter rejectedCounter;
    private final OpsSignalPort opsSignalPort;

    /** 운영 컨텍스트용 — Spring 이 이 생성자로 실 OpsSignalPort 빈을 주입한다. */
    @Autowired
    public DecreaseProductStockService(LoadProductPort loadPort,
                                       SaveProductPort savePort,
                                       TransactionTemplate transactionTemplate,
                                       MeterRegistry meterRegistry,
                                       OpsSignalPort opsSignalPort) {
        this.loadPort = loadPort;
        this.savePort = savePort;
        this.transactionTemplate = transactionTemplate;
        this.opsSignalPort = opsSignalPort;
        this.successCounter = Counter.builder("product.stock.decrease.success")
                .description("일반 상품 재고 차감 성공 누적")
                .register(meterRegistry);
        this.rejectedCounter = Counter.builder("product.stock.decrease.rejected")
                .description("재고 부족·단종 등으로 차감 거절된 누적")
                .register(meterRegistry);
    }

    /** 기존 테스트/수동 조립 호환 편의 생성자 — ops 신호는 no-op. */
    public DecreaseProductStockService(LoadProductPort loadPort,
                                       SaveProductPort savePort,
                                       TransactionTemplate transactionTemplate,
                                       MeterRegistry meterRegistry) {
        this(loadPort, savePort, transactionTemplate, meterRegistry, new NoOpOpsSignalPublisher());
    }

    @Override
    public Product decrease(Long productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("차감 수량은 양수여야 합니다");
        }
        Product result = transactionTemplate.execute(status -> {
            int affected = savePort.decreaseStockIfAvailable(productId, quantity);
            if (affected == 0) {
                throw classifyFailure(productId, quantity);
            }
            return loadPort.findById(productId).orElseThrow(() -> new IllegalStateException(
                    "재고 차감 후 product 사라짐 (id=" + productId + ")"));
        });
        successCounter.increment();
        return result;
    }

    /**
     * 원자적 차감 실패(영향 행 0) 원인 분류. 경합이 아니라 '차감 불가' 상태이므로 재시도 대상이 아니다.
     */
    private RuntimeException classifyFailure(Long productId, int quantity) {
        rejectedCounter.increment();
        Product current = loadPort.findById(productId).orElse(null);
        if (current == null) {
            return new ProductNotFoundException(productId);
        }
        if (current.getStatus() == ProductStatus.DISCONTINUED) {
            return new IllegalStateException("단종된 상품은 차감할 수 없습니다: id=" + productId);
        }
        // 운영 관제 신호 — 구매 시 재고 부족(초과 수요). best-effort(절대 throw 안 함).
        opsSignalPort.emit(OpsSignalCategory.STOCK_DEPLETED, "product", String.valueOf(productId),
                Map.of("requested", quantity, "available", current.getStockQuantity()));
        return new InsufficientStockException(
                "재고 부족: productId=" + productId + ", 요청=" + quantity
                        + ", 가용=" + current.getStockQuantity());
    }
}
