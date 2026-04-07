package github.lms.lemuel.returns.application.port.out;

import github.lms.lemuel.returns.domain.ReturnOrder;

import java.util.List;
import java.util.Optional;

/**
 * 반품/교환 조회 Outbound Port
 */
public interface LoadReturnPort {

    Optional<ReturnOrder> findById(Long returnId);

    List<ReturnOrder> findByOrderId(Long orderId);

    List<ReturnOrder> findByUserId(Long userId);

    List<ReturnOrder> findByStatus(String status);
}
