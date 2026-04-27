package github.lms.lemuel.payment.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataRefundJpaRepository extends JpaRepository<RefundJpaEntity, Long> {

    Optional<RefundJpaEntity> findByPaymentIdAndIdempotencyKey(Long paymentId, String idempotencyKey);

    List<RefundJpaEntity> findByPaymentIdOrderByRequestedAtDesc(Long paymentId);
}
