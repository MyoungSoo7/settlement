package github.lms.lemuel.shipping.adapter.in.web.dto;

import github.lms.lemuel.shipping.domain.ShippingAddress;

public record ShippingAddressResponse(
        Long id,
        Long userId,
        String recipientName,
        String phone,
        String zipCode,
        String address,
        String addressDetail,
        boolean isDefault
) {
    public static ShippingAddressResponse from(ShippingAddress domain) {
        return new ShippingAddressResponse(
                domain.getId(),
                domain.getUserId(),
                domain.getRecipientName(),
                domain.getPhone(),
                domain.getZipCode(),
                domain.getAddress(),
                domain.getAddressDetail(),
                domain.isDefault()
        );
    }
}
