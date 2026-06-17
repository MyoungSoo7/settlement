package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.CreateMultiItemOrderUseCase;
import github.lms.lemuel.order.application.port.out.LoadUserForOrderPort;
import github.lms.lemuel.order.application.port.out.PublishOrderEventPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.application.port.out.SendOrderNotificationPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderItem;
import github.lms.lemuel.order.domain.exception.UserNotExistsException;
import github.lms.lemuel.product.application.port.in.DecreaseVariantStockUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 다건 주문 생성 서비스.
 *
 * <p>흐름:
 * <ol>
 *   <li>사용자 존재 검증</li>
 *   <li>각 라인에 대해 상품/SKU 조회 → 단가·이름 스냅샷 추출</li>
 *   <li>SKU 가 지정된 라인은 재고 차감 (Optimistic Lock 자동 재시도)</li>
 *   <li>OrderItem 들 생성 + Order.createMultiItem 으로 합계 자동 계산</li>
 *   <li>저장 → 알림 발송</li>
 * </ol>
 *
 * <p>재고 차감과 Order 저장이 같은 트랜잭션이므로 PG 결제 실패 시 롤백되어
 * "재고는 빠졌는데 주문 안 만들어진" 정합성 깨짐을 방지한다.
 */
@Service
@Transactional
public class CreateMultiItemOrderService implements CreateMultiItemOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateMultiItemOrderService.class);

    private final LoadUserForOrderPort loadUserPort;
    private final LoadProductPort loadProductPort;
    private final LoadProductVariantPort loadVariantPort;
    private final DecreaseVariantStockUseCase decreaseStockUseCase;
    private final SaveOrderPort saveOrderPort;
    private final SendOrderNotificationPort sendNotificationPort;
    private final PublishOrderEventPort publishOrderEventPort;

    public CreateMultiItemOrderService(LoadUserForOrderPort loadUserPort,
                                       LoadProductPort loadProductPort,
                                       LoadProductVariantPort loadVariantPort,
                                       DecreaseVariantStockUseCase decreaseStockUseCase,
                                       SaveOrderPort saveOrderPort,
                                       SendOrderNotificationPort sendNotificationPort,
                                       PublishOrderEventPort publishOrderEventPort) {
        this.loadUserPort = loadUserPort;
        this.loadProductPort = loadProductPort;
        this.loadVariantPort = loadVariantPort;
        this.decreaseStockUseCase = decreaseStockUseCase;
        this.saveOrderPort = saveOrderPort;
        this.sendNotificationPort = sendNotificationPort;
        this.publishOrderEventPort = publishOrderEventPort;
    }

    @Override
    public Order create(Long userId, List<Line> lines) {
        log.info("다건 주문 생성: userId={}, lines={}", userId, lines.size());

        String userEmail = loadUserPort.findEmailById(userId)
                .orElseThrow(() -> new UserNotExistsException(userId));

        List<OrderItem> items = new ArrayList<>(lines.size());
        for (Line line : lines) {
            Product product = loadProductPort.findById(line.productId())
                    .orElseThrow(() -> new ProductNotFoundException(line.productId()));

            BigDecimal unitPrice = product.getPrice();
            String productName = product.getName();
            String sku = null;

            // SKU 라인이면 variant 조회 + 재고 차감 + 가산금 반영
            if (line.variantId() != null) {
                ProductVariant variant = loadVariantPort.loadById(line.variantId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "ProductVariant not found: " + line.variantId()));
                if (!variant.getProductId().equals(product.getId())) {
                    throw new IllegalArgumentException(
                            "variant 가 product 에 속하지 않음: variant=" + line.variantId()
                                    + ", product=" + line.productId());
                }
                sku = variant.getSku();
                unitPrice = unitPrice.add(variant.getAdditionalPrice());
                decreaseStockUseCase.decrease(line.variantId(), line.quantity());
            }

            items.add(OrderItem.newItem(line.productId(), line.variantId(), sku,
                    productName, unitPrice, line.quantity()));
        }

        Order order = Order.createMultiItem(userId, items);
        Order saved = saveOrderPort.save(order);

        // ADR 0020 Phase 3b — settlement order 프로젝션 동기화용 OrderCreated 발행(같은 트랜잭션 Outbox)
        publishOrderEventPort.publishOrderCreated(
                saved.getId(), saved.getUserId(), saved.getProductId(),
                saved.getStatus().name(), saved.getAmount(), saved.getCreatedAt());

        log.info("다건 주문 생성 완료: orderId={}, items={}, amount={}",
                saved.getId(), saved.getItems().size(), saved.getAmount());

        sendNotificationPort.sendOrderConfirmation(userEmail, saved);
        return saved;
    }
}
