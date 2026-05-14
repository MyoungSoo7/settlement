package github.lms.lemuel.ledger.adapter.out.persistence;

import github.lms.lemuel.ledger.application.dto.SettlementSummary;
import github.lms.lemuel.ledger.application.port.out.LoadSettlementForLedgerPort;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementJpaEntity;
import github.lms.lemuel.settlement.adapter.out.persistence.SpringDataSettlementJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * settlement_jpa_entity 를 어댑터 레벨에서만 read 하여 ledger 에 SettlementSummary 로 전달.
 *
 * <p>ledger application 레이어는 settlement 도메인·엔티티를 import 하지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class SettlementForLedgerPersistenceAdapter implements LoadSettlementForLedgerPort {

    private final SpringDataSettlementJpaRepository settlementRepository;

    @Override
    public Optional<SettlementSummary> findById(Long settlementId) {
        return settlementRepository.findById(settlementId).map(this::toSummary);
    }

    private SettlementSummary toSummary(SettlementJpaEntity e) {
        return new SettlementSummary(
                e.getId(),
                e.getPaymentAmount(),
                e.getCommission(),
                e.getNetAmount(),
                e.getSettlementDate(),
                e.getStatus()
        );
    }
}
