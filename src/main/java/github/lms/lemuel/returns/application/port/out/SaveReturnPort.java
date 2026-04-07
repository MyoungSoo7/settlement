package github.lms.lemuel.returns.application.port.out;

import github.lms.lemuel.returns.domain.ReturnOrder;

/**
 * 반품/교환 저장 Outbound Port
 */
public interface SaveReturnPort {

    ReturnOrder save(ReturnOrder returnOrder);
}
