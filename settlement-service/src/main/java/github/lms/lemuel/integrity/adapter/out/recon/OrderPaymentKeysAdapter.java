package github.lms.lemuel.integrity.adapter.out.recon;

import github.lms.lemuel.integrity.application.port.out.KeyChecksum;
import github.lms.lemuel.integrity.application.port.out.LoadOrderPaymentKeysPort;
import github.lms.lemuel.integrity.application.port.out.PaymentKey;
import github.lms.lemuel.recon.OrderReconClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * INV-12 order 결제 키 어댑터 — 기존 {@link OrderReconClient}(X-Internal-Api-Key 게이트·타임아웃·재시도)를
 * 재사용해 order 내부 대사 API 를 호출한다. 새 인증/전송 경로를 만들지 않는다 (OrderCompletedRefundsAdapter 와 동형).
 */
@Component
public class OrderPaymentKeysAdapter implements LoadOrderPaymentKeysPort {

    private final OrderReconClient client;

    public OrderPaymentKeysAdapter(OrderReconClient client) {
        this.client = client;
    }

    @Override
    public KeyChecksum checksum(LocalDate date) {
        OrderReconClient.PaymentKeyChecksum c = client.paymentKeysChecksum(date);
        return new KeyChecksum(c.count(), c.amountSum(), c.idChecksum());
    }

    @Override
    public List<PaymentKey> keys(LocalDate date, long afterId, int limit) {
        return client.paymentKeys(date, afterId, limit).stream()
                .map(r -> new PaymentKey(r.paymentId(), r.amount()))
                .toList();
    }
}
