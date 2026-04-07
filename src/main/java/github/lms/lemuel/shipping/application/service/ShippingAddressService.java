package github.lms.lemuel.shipping.application.service;

import github.lms.lemuel.shipping.application.port.in.ShippingAddressUseCase;
import github.lms.lemuel.shipping.application.port.out.LoadShippingAddressPort;
import github.lms.lemuel.shipping.application.port.out.SaveShippingAddressPort;
import github.lms.lemuel.shipping.domain.ShippingAddress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ShippingAddressService implements ShippingAddressUseCase {

    private final LoadShippingAddressPort loadShippingAddressPort;
    private final SaveShippingAddressPort saveShippingAddressPort;

    @Override
    public ShippingAddress createAddress(CreateAddressCommand command) {
        log.info("배송지 생성: userId={}", command.userId());

        ShippingAddress address = ShippingAddress.create(
                command.userId(),
                command.recipientName(),
                command.phone(),
                command.zipCode(),
                command.address(),
                command.addressDetail()
        );

        // 사용자의 첫 배송지라면 기본 배송지로 설정
        List<ShippingAddress> existing = loadShippingAddressPort.findByUserId(command.userId());
        if (existing.isEmpty()) {
            address.setAsDefault();
        }

        ShippingAddress saved = saveShippingAddressPort.save(address);
        log.info("배송지 생성 완료: addressId={}", saved.getId());
        return saved;
    }

    @Override
    public ShippingAddress updateAddress(Long id, UpdateAddressCommand command) {
        log.info("배송지 수정: addressId={}", id);

        ShippingAddress address = loadShippingAddressPort.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("배송지를 찾을 수 없습니다. id=" + id));

        address.updateInfo(
                command.recipientName(),
                command.phone(),
                command.zipCode(),
                command.address(),
                command.addressDetail()
        );

        ShippingAddress updated = saveShippingAddressPort.save(address);
        log.info("배송지 수정 완료: addressId={}", id);
        return updated;
    }

    @Override
    public void deleteAddress(Long id) {
        log.info("배송지 삭제: addressId={}", id);

        ShippingAddress address = loadShippingAddressPort.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("배송지를 찾을 수 없습니다. id=" + id));

        saveShippingAddressPort.deleteById(id);

        // 기본 배송지를 삭제한 경우, 남은 배송지 중 첫 번째를 기본으로 설정
        if (address.isDefault()) {
            List<ShippingAddress> remaining = loadShippingAddressPort.findByUserId(address.getUserId());
            if (!remaining.isEmpty()) {
                ShippingAddress first = remaining.get(0);
                first.setAsDefault();
                saveShippingAddressPort.save(first);
            }
        }

        log.info("배송지 삭제 완료: addressId={}", id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShippingAddress> getUserAddresses(Long userId) {
        return loadShippingAddressPort.findByUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public ShippingAddress getDefaultAddress(Long userId) {
        return loadShippingAddressPort.findDefaultByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("기본 배송지가 설정되어 있지 않습니다. userId=" + userId));
    }

    @Override
    public void setDefaultAddress(Long userId, Long addressId) {
        log.info("기본 배송지 설정: userId={}, addressId={}", userId, addressId);

        ShippingAddress target = loadShippingAddressPort.findById(addressId)
                .orElseThrow(() -> new IllegalArgumentException("배송지를 찾을 수 없습니다. id=" + addressId));

        if (!target.getUserId().equals(userId)) {
            throw new IllegalStateException("본인의 배송지만 기본 배송지로 설정할 수 있습니다.");
        }

        // 기존 기본 배송지 해제
        loadShippingAddressPort.findDefaultByUserId(userId)
                .ifPresent(existing -> {
                    existing.unsetDefault();
                    saveShippingAddressPort.save(existing);
                });

        // 새 기본 배송지 설정
        target.setAsDefault();
        saveShippingAddressPort.save(target);

        log.info("기본 배송지 설정 완료: addressId={}", addressId);
    }
}
