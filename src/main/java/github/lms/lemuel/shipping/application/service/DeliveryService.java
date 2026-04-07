package github.lms.lemuel.shipping.application.service;

import github.lms.lemuel.shipping.application.port.in.DeliveryUseCase;
import github.lms.lemuel.shipping.application.port.out.LoadDeliveryPort;
import github.lms.lemuel.shipping.application.port.out.SaveDeliveryPort;
import github.lms.lemuel.shipping.domain.Delivery;
import github.lms.lemuel.shipping.domain.DeliveryStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DeliveryService implements DeliveryUseCase {

    private final LoadDeliveryPort loadDeliveryPort;
    private final SaveDeliveryPort saveDeliveryPort;

    @Override
    public Delivery createDelivery(CreateDeliveryCommand command) {
        log.info("배송 생성: orderId={}", command.orderId());

        Delivery delivery = Delivery.create(
                command.orderId(),
                command.addressId(),
                command.recipientName(),
                command.phone(),
                command.address(),
                command.shippingFee()
        );

        Delivery saved = saveDeliveryPort.save(delivery);
        log.info("배송 생성 완료: deliveryId={}", saved.getId());
        return saved;
    }

    @Override
    public Delivery shipDelivery(Long deliveryId, String trackingNumber, String carrier) {
        log.info("배송 출발: deliveryId={}, trackingNumber={}", deliveryId, trackingNumber);

        Delivery delivery = loadDeliveryPort.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("배송 정보를 찾을 수 없습니다. id=" + deliveryId));

        delivery.ship(trackingNumber, carrier);
        Delivery saved = saveDeliveryPort.save(delivery);
        log.info("배송 출발 처리 완료: deliveryId={}", deliveryId);
        return saved;
    }

    @Override
    public Delivery updateStatus(Long deliveryId, DeliveryStatus status) {
        log.info("배송 상태 변경: deliveryId={}, status={}", deliveryId, status);

        Delivery delivery = loadDeliveryPort.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("배송 정보를 찾을 수 없습니다. id=" + deliveryId));

        switch (status) {
            case SHIPPED          -> delivery.ship(delivery.getTrackingNumber(), delivery.getCarrier());
            case IN_TRANSIT       -> delivery.startTransit();
            case OUT_FOR_DELIVERY -> delivery.outForDelivery();
            case DELIVERED        -> delivery.deliver();
            case CANCELED         -> delivery.cancel();
            default               -> throw new IllegalArgumentException("지원하지 않는 상태입니다: " + status);
        }

        Delivery saved = saveDeliveryPort.save(delivery);
        log.info("배송 상태 변경 완료: deliveryId={}, newStatus={}", deliveryId, status);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Delivery getDelivery(Long id) {
        return loadDeliveryPort.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("배송 정보를 찾을 수 없습니다. id=" + id));
    }

    @Override
    @Transactional(readOnly = true)
    public Delivery getDeliveryByOrderId(Long orderId) {
        return loadDeliveryPort.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalArgumentException("해당 주문의 배송 정보를 찾을 수 없습니다. orderId=" + orderId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Delivery> getDeliveriesByStatus(DeliveryStatus status) {
        return loadDeliveryPort.findByStatus(status);
    }
}
