package github.lms.lemuel.settlement.adapter.out.readmodel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface SettlementPaymentReadModelRepository extends JpaRepository<SettlementPaymentReadModel, Long> {

    List<SettlementPaymentReadModel> findByCapturedAtBetweenAndStatus(
            LocalDateTime startInclusive,
            LocalDateTime endExclusive,
            String status
    );
}
