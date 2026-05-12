package github.lms.lemuel.chargeback.application.port.out;

import github.lms.lemuel.chargeback.domain.Chargeback;

public interface SaveChargebackPort {

    Chargeback save(Chargeback chargeback);
}
