package github.lms.lemuel.shipping.adapter.out.order;

import github.lms.lemuel.order.application.port.in.ChangeOrderStatusUseCase;
import github.lms.lemuel.shipping.application.port.out.RestoreReturnedOrderStockPort;
import org.springframework.stereotype.Component;

/**
 * shipping → order 방향 어댑터. order 의 JPA 엔티티·리포지토리를 직접 참조하지 않고
 * inbound 유스케이스만 호출한다(payment 의 {@code OrderAdapter} 와 동형).
 */
@Component
public class ReturnedOrderStockAdapter implements RestoreReturnedOrderStockPort {

    private final ChangeOrderStatusUseCase changeOrderStatusUseCase;

    public ReturnedOrderStockAdapter(ChangeOrderStatusUseCase changeOrderStatusUseCase) {
        this.changeOrderStatusUseCase = changeOrderStatusUseCase;
    }

    @Override
    public void restoreReturnedOrderStock(Long orderId) {
        changeOrderStatusUseCase.restoreStockOnReturn(orderId);
    }
}
