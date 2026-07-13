package github.lms.lemuel.shipping.application.service;

import github.lms.lemuel.shipping.application.port.in.ShippingUseCase;
import github.lms.lemuel.shipping.application.port.out.LoadShipmentPort;
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

    public ShippingService(LoadShipmentPort loadPort, SaveShipmentPort savePort) {
        this.loadPort = loadPort;
        this.savePort = savePort;
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
        return savePort.save(s);
    }

    private Shipment mustExist(Long orderId) {
        return loadPort.loadByOrderId(orderId)
                .orElseThrow(() -> new ShipmentInvariantViolationException("배송 없음: orderId=" + orderId));
    }
}
