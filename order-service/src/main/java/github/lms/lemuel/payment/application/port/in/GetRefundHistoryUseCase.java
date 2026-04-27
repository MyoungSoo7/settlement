package github.lms.lemuel.payment.application.port.in;

import github.lms.lemuel.payment.domain.Refund;

import java.util.List;

public interface GetRefundHistoryUseCase {

    List<Refund> getRefundsByPaymentId(Long paymentId);
}
