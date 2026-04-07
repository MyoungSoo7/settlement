package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.CreateOrderUseCase;
import github.lms.lemuel.order.application.port.out.LoadProductForOrderPort;
import github.lms.lemuel.order.application.port.out.LoadUserForOrderPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.application.port.out.SendOrderNotificationPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderItem;
import github.lms.lemuel.order.domain.exception.UserNotExistsException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CreateOrderService implements CreateOrderUseCase {

    private final LoadUserForOrderPort loadUserForOrderPort;
    private final LoadProductForOrderPort loadProductForOrderPort;
    private final SaveOrderPort saveOrderPort;
    private final SendOrderNotificationPort sendOrderNotificationPort;

    @Override
    public Order createOrder(CreateOrderCommand command) {
        log.info("주문 생성 시작: userId={}, productId={}, amount={}", command.userId(), command.productId(), command.amount());

        // 1. 사용자 존재 확인 및 이메일 조회
        String userEmail = loadUserForOrderPort.findEmailById(command.userId())
                .orElseThrow(() -> {
                    log.warn("존재하지 않는 사용자: userId={}", command.userId());
                    return new UserNotExistsException(command.userId());
                });

        // 2. Order 도메인 생성 (도메인 검증 수행)
        Order order = Order.create(command.userId(), command.productId(), command.amount());

        // 3. 저장
        Order savedOrder = saveOrderPort.save(order);

        log.info("주문 생성 완료: orderId={}, userId={}, amount={}",
                savedOrder.getId(), savedOrder.getUserId(), savedOrder.getAmount());

        // 4. 알림 발송
        sendOrderNotificationPort.sendOrderConfirmation(userEmail, savedOrder);

        return savedOrder;
    }

    @Override
    public Order createMultiItemOrder(CreateMultiItemOrderCommand command) {
        log.info("복수 상품 주문 생성 시작: userId={}, itemCount={}", command.userId(), command.items().size());

        // 1. 사용자 존재 확인 및 이메일 조회
        String userEmail = loadUserForOrderPort.findEmailById(command.userId())
                .orElseThrow(() -> {
                    log.warn("존재하지 않는 사용자: userId={}", command.userId());
                    return new UserNotExistsException(command.userId());
                });

        // 2. 상품 가격 조회 및 OrderItem 생성
        List<OrderItem> items = new ArrayList<>();
        for (OrderItemCommand itemCmd : command.items()) {
            BigDecimal unitPrice = loadProductForOrderPort.findPriceById(itemCmd.productId())
                    .orElseThrow(() -> {
                        log.warn("존재하지 않는 상품: productId={}", itemCmd.productId());
                        return new IllegalArgumentException("Product not found: " + itemCmd.productId());
                    });

            OrderItem item = OrderItem.create(itemCmd.productId(), itemCmd.quantity(), unitPrice);
            items.add(item);
        }

        // 3. Order 도메인 생성 (shippingFee, discountAmount는 향후 쿠폰/배송 시스템 연동)
        BigDecimal shippingFee = BigDecimal.ZERO;
        BigDecimal discountAmount = BigDecimal.ZERO;

        Order order = Order.createMultiItem(command.userId(), items, shippingFee, discountAmount);

        // 배송지, 쿠폰 설정
        if (command.shippingAddressId() != null) {
            order.setShippingAddressId(command.shippingAddressId());
        }
        if (command.couponCode() != null) {
            order.setCouponCode(command.couponCode());
        }

        // 4. 저장
        Order savedOrder = saveOrderPort.save(order);

        log.info("복수 상품 주문 생성 완료: orderId={}, userId={}, totalAmount={}, itemCount={}",
                savedOrder.getId(), savedOrder.getUserId(), savedOrder.getTotalAmount(), savedOrder.getItemCount());

        // 5. 알림 발송
        sendOrderNotificationPort.sendOrderConfirmation(userEmail, savedOrder);

        return savedOrder;
    }
}
