package github.lms.lemuel.integrity.adapter.out.recon;

import github.lms.lemuel.integrity.application.port.out.LoadCompletedRefundsPort;
import github.lms.lemuel.recon.OrderReconClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * INV-8 완료 환불 목록 어댑터 — 기존 {@link OrderReconClient}(X-Internal-Api-Key 게이트)를
 * 재사용해 order 내부 대사 API 를 호출한다. 새 인증 경로를 만들지 않는다.
 */
@Component
public class OrderCompletedRefundsAdapter implements LoadCompletedRefundsPort {

    private final OrderReconClient client;

    public OrderCompletedRefundsAdapter(OrderReconClient client) {
        this.client = client;
    }

    @Override
    public List<CompletedRefund> refundsCompleted(LocalDate from, LocalDate to, int limit) {
        return client.refundsCompleted(from, to, limit).stream()
                .map(r -> new CompletedRefund(r.refundId(), r.amount(), r.completedDate()))
                .toList();
    }
}
