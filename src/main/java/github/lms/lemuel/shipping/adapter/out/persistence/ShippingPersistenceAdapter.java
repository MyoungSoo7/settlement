package github.lms.lemuel.shipping.adapter.out.persistence;

import github.lms.lemuel.shipping.application.port.out.LoadDeliveryPort;
import github.lms.lemuel.shipping.application.port.out.LoadShippingAddressPort;
import github.lms.lemuel.shipping.application.port.out.SaveDeliveryPort;
import github.lms.lemuel.shipping.application.port.out.SaveShippingAddressPort;
import github.lms.lemuel.shipping.domain.Delivery;
import github.lms.lemuel.shipping.domain.DeliveryStatus;
import github.lms.lemuel.shipping.domain.ShippingAddress;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ShippingPersistenceAdapter
        implements LoadShippingAddressPort, SaveShippingAddressPort, LoadDeliveryPort, SaveDeliveryPort {

    private final SpringDataShippingAddressRepository addressRepository;
    private final SpringDataDeliveryRepository deliveryRepository;

    // ── LoadShippingAddressPort ─────────────────────────────────────────

    @Override
    public Optional<ShippingAddress> findById(Long id) {
        return addressRepository.findById(id).map(ShippingPersistenceMapper::toDomain);
    }

    @Override
    public List<ShippingAddress> findByUserId(Long userId) {
        return addressRepository.findByUserId(userId)
                .stream().map(ShippingPersistenceMapper::toDomain).collect(Collectors.toList());
    }

    @Override
    public Optional<ShippingAddress> findDefaultByUserId(Long userId) {
        return addressRepository.findByUserIdAndIsDefaultTrue(userId)
                .map(ShippingPersistenceMapper::toDomain);
    }

    // ── SaveShippingAddressPort ─────────────────────────────────────────

    @Override
    public ShippingAddress save(ShippingAddress address) {
        ShippingAddressJpaEntity entity = ShippingPersistenceMapper.toEntity(address);
        ShippingAddressJpaEntity saved = addressRepository.save(entity);
        return ShippingPersistenceMapper.toDomain(saved);
    }

    @Override
    public void deleteById(Long id) {
        addressRepository.deleteById(id);
    }

    // ── LoadDeliveryPort ────────────────────────────────────────────────

    @Override
    public Optional<Delivery> findById(Long id) {
        return deliveryRepository.findById(id).map(ShippingPersistenceMapper::toDomain);
    }

    @Override
    public Optional<Delivery> findByOrderId(Long orderId) {
        return deliveryRepository.findByOrderId(orderId).map(ShippingPersistenceMapper::toDomain);
    }

    @Override
    public List<Delivery> findByStatus(DeliveryStatus status) {
        return deliveryRepository.findByStatus(status.name())
                .stream().map(ShippingPersistenceMapper::toDomain).collect(Collectors.toList());
    }

    @Override
    public Optional<Delivery> findByTrackingNumber(String trackingNumber) {
        return deliveryRepository.findByTrackingNumber(trackingNumber)
                .map(ShippingPersistenceMapper::toDomain);
    }

    // ── SaveDeliveryPort ────────────────────────────────────────────────

    @Override
    public Delivery save(Delivery delivery) {
        DeliveryJpaEntity entity = ShippingPersistenceMapper.toEntity(delivery);
        DeliveryJpaEntity saved = deliveryRepository.save(entity);
        return ShippingPersistenceMapper.toDomain(saved);
    }
}
