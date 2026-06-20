package github.lms.lemuel.chargeback.adapter.out.persistence;

import github.lms.lemuel.chargeback.domain.ChargebackStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataChargebackRepository extends JpaRepository<ChargebackJpaEntity, Long> {

    Optional<ChargebackJpaEntity> findByPgChargebackId(String pgChargebackId);

    List<ChargebackJpaEntity> findByPaymentIdOrderByRaisedAtDesc(Long paymentId);

    List<ChargebackJpaEntity> findByStatusOrderByRaisedAtDesc(ChargebackStatus status, Pageable pageable);
}
