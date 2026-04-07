package github.lms.lemuel.shipping.application.port.out;

import github.lms.lemuel.shipping.domain.ShippingAddress;

public interface SaveShippingAddressPort {

    ShippingAddress save(ShippingAddress address);

    void deleteById(Long id);
}
