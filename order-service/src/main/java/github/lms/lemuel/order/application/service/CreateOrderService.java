package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.CreateOrderUseCase;
import github.lms.lemuel.order.application.port.out.LoadUserForOrderPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.application.port.out.SendOrderNotificationPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.exception.UserNotExistsException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CreateOrderService implements CreateOrderUseCase {

    private final LoadUserForOrderPort loadUserForOrderPort;
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
}
