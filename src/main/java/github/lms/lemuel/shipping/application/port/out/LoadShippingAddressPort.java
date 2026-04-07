package github.lms.lemuel.shipping.application.port.out;

import github.lms.lemuel.shipping.domain.ShippingAddress;

import java.util.List;
import java.util.Optional;

public interface LoadShippingAddressPort {

    Optional<ShippingAddress> findById(Long id);

    List<ShippingAddress> findByUserId(Long userId);

    Optional<ShippingAddress> findDefaultByUserId(Long userId);
}
