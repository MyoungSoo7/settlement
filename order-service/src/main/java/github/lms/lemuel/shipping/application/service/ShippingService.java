package github.lms.lemuel.shipping.application.service;

import github.lms.lemuel.shipping.application.port.in.ShippingUseCase;
import github.lms.lemuel.shipping.application.port.out.LoadShipmentPort;
import github.lms.lemuel.shipping.application.port.out.RestoreReturnedOrderStockPort;
import github.lms.lemuel.shipping.application.port.out.SaveShipmentPort;
import github.lms.lemuel.shipping.domain.Shipment;
import github.lms.lemuel.shipping.domain.ShippingAddress;
import github.lms.lemuel.shipping.domain.exception.ShipmentInvariantViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ShippingService implements ShippingUseCase {

    private final LoadShipmentPort loadPort;
    private final SaveShipmentPort savePort;
    private final RestoreReturnedOrderStockPort restoreStockPort;

    public ShippingService(LoadShipmentPort loadPort, SaveShipmentPort savePort,
                           RestoreReturnedOrderStockPort restoreStockPort) {
        this.loadPort = loadPort;
        this.savePort = savePort;
        this.restoreStockPort = restoreStockPort;
    }

    @Override
    public Shipment createForOrder(Long orderId, ShippingAddress address) {
        loadPort.loadByOrderId(orderId).ifPresent(s -> {
            throw new ShipmentInvariantViolationException("이미 배송이 생성된 주문: " + orderId);
        });
        return savePort.save(Shipment.createPending(orderId, address));
    }

    @Override
    public Shipment changeAddress(Long orderId, ShippingAddress newAddress) {
        Shipment s = mustExist(orderId);
        s.changeAddress(newAddress);
        return savePort.save(s);
    }

    @Override
    public Shipment ship(Long orderId, String carrier, String trackingNumber) {
        Shipment s = mustExist(orderId);
        s.ship(carrier, trackingNumber);
        return savePort.save(s);
    }

    @Override
    public Shipment markInTransit(Long orderId) {
        Shipment s = mustExist(orderId);
        s.markInTransit();
        return savePort.save(s);
    }

    @Override
    public Shipment markDelivered(Long orderId) {
        Shipment s = mustExist(orderId);
        s.markDelivered();
        return savePort.save(s);
    }

    @Override
    public Shipment markReturned(Long orderId) {
        Shipment s = mustExist(orderId);
        s.returnShipment();
        Shipment saved = savePort.save(s);
        // 물건이 실제로 돌아온 것이 확인되는 유일한 지점 — 배송 후 환불로 보류됐던 재고를 여기서 되돌린다.
        // 이미 원복된 주문이면 order 도메인이 no-op 처리한다(멱등).
        restoreStockPort.restoreReturnedOrderStock(orderId);
        return saved;
    }

    private Shipment mustExist(Long orderId) {
        return loadPort.loadByOrderId(orderId)
                .orElseThrow(() -> new ShipmentInvariantViolationException("배송 없음: orderId=" + orderId));
    }
}
