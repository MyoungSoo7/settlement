package github.lms.lemuel.projectionbackfill;

import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.PublishOrderEventPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.application.port.out.LoadSellerSettlementMetaPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.PublishProductEventPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.PublishUserEventPort;
import github.lms.lemuel.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기존 order 데이터를 도메인 이벤트로 재발행해 settlement 프로젝션을 시드한다(Phase 4 Chunk 3).
 *
 * <p>발행은 outbox(Transactional Outbox)에 적재되고 OutboxPublisherScheduler 가 Kafka 로 보낸다.
 * settlement 컨슈머가 (consumer_group,event_id) 멱등 + view upsert 로 처리하므로 여러 번 실행해도 안전.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementProjectionBackfillService implements BackfillSettlementProjectionsUseCase {

    private final LoadUserPort loadUserPort;
    private final LoadProductPort loadProductPort;
    private final LoadOrderPort loadOrderPort;
    private final LoadPaymentPort loadPaymentPort;

    private final PublishUserEventPort publishUserEventPort;
    private final PublishProductEventPort publishProductEventPort;
    private final PublishOrderEventPort publishOrderEventPort;
    private final PublishEventPort publishEventPort;
    private final LoadSellerSettlementMetaPort loadSellerSettlementMetaPort;

    @Override
    @Transactional
    public BackfillResult backfillAll() {
        int users = 0;
        for (User u : loadUserPort.findAll()) {
            publishUserEventPort.publishUserRegistered(u.getId(), u.getEmail());
            users++;
        }

        int products = 0;
        for (Product p : loadProductPort.findAll()) {
            publishProductEventPort.publishProductChanged(p.getId(), p.getName());
            products++;
        }

        int orders = 0;
        for (Order o : loadOrderPort.findAll()) {
            publishOrderEventPort.publishOrderCreated(
                    o.getId(), o.getUserId(), o.getProductId(),
                    o.getStatus().name(), o.getAmount(), o.getCreatedAt());
            orders++;
        }

        int payments = 0;
        for (PaymentDomain pay : loadPaymentPort.findAllCaptured()) {
            publishEventPort.publishPaymentCaptured(
                    pay.getId(), pay.getOrderId(), pay.getAmount(), pay.getCapturedAt(),
                    pay.getPaymentMethod(), pay.getPgTransactionId(),
                    loadSellerSettlementMetaPort.findByPaymentId(pay.getId()).orElse(null));
            payments++;
        }

        log.info("settlement 프로젝션 백필 발행 완료: users={}, products={}, orders={}, payments={}",
                users, products, orders, payments);
        return new BackfillResult(users, products, orders, payments);
    }
}
