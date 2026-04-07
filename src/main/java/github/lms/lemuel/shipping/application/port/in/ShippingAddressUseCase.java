package github.lms.lemuel.shipping.application.port.in;

import github.lms.lemuel.shipping.domain.ShippingAddress;

import java.util.List;

public interface ShippingAddressUseCase {

    ShippingAddress createAddress(CreateAddressCommand command);

    ShippingAddress updateAddress(Long id, UpdateAddressCommand command);

    void deleteAddress(Long id);

    List<ShippingAddress> getUserAddresses(Long userId);

    ShippingAddress getDefaultAddress(Long userId);

    void setDefaultAddress(Long userId, Long addressId);

    record CreateAddressCommand(
            Long userId,
            String recipientName,
            String phone,
            String zipCode,
            String address,
            String addressDetail
    ) {}

    record UpdateAddressCommand(
            String recipientName,
            String phone,
            String zipCode,
            String address,
            String addressDetail
    ) {}
}
