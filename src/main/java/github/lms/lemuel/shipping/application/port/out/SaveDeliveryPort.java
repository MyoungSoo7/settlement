package github.lms.lemuel.shipping.application.port.out;

import github.lms.lemuel.shipping.domain.Delivery;

public interface SaveDeliveryPort {

    Delivery save(Delivery delivery);
}
