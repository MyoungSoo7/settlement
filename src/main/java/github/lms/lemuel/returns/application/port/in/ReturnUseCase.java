package github.lms.lemuel.returns.application.port.in;

import github.lms.lemuel.returns.domain.ReturnOrder;
import github.lms.lemuel.returns.domain.ReturnReason;
import github.lms.lemuel.returns.domain.ReturnStatus;
import github.lms.lemuel.returns.domain.ReturnType;

import java.math.BigDecimal;
import java.util.List;

public interface ReturnUseCase {

    ReturnOrder createReturn(CreateReturnCommand cmd);

    ReturnOrder approveReturn(Long returnId);

    ReturnOrder rejectReturn(Long returnId, String reason);

    ReturnOrder shipReturn(Long returnId, String trackingNumber, String carrier);

    ReturnOrder receiveReturn(Long returnId);

    ReturnOrder completeReturn(Long returnId);

    ReturnOrder cancelReturn(Long returnId);

    ReturnOrder getReturn(Long returnId);

    List<ReturnOrder> getReturnsByOrderId(Long orderId);

    List<ReturnOrder> getReturnsByUserId(Long userId);

    List<ReturnOrder> getReturnsByStatus(ReturnStatus status);

    record CreateReturnCommand(
            Long orderId,
            Long userId,
            ReturnType type,
            ReturnReason reason,
            String reasonDetail,
            BigDecimal refundAmount
    ) {
        public CreateReturnCommand {
            if (orderId == null) {
                throw new IllegalArgumentException("Order ID cannot be null");
            }
            if (userId == null) {
                throw new IllegalArgumentException("User ID cannot be null");
            }
            if (type == null) {
                throw new IllegalArgumentException("Return type cannot be null");
            }
            if (reason == null) {
                throw new IllegalArgumentException("Return reason cannot be null");
            }
        }
    }
}
