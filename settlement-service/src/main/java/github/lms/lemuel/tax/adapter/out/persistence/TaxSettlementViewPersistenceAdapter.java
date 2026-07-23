package github.lms.lemuel.tax.adapter.out.persistence;

import github.lms.lemuel.settlement.adapter.out.persistence.SettlementJpaEntity;
import github.lms.lemuel.settlement.adapter.out.persistence.SpringDataSettlementJpaRepository;
import github.lms.lemuel.tax.application.dto.TaxSettlementView;
import github.lms.lemuel.tax.application.port.out.LoadSettlementForTaxPort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * settlement_jpa_entity 를 어댑터 레벨에서만 read 하여 세무 도메인에 {@link TaxSettlementView} 로 전달한다
 * (SettlementForLedgerPersistenceAdapter 와 동형 — 세무 application 은 settlement 엔티티를 import 하지 않는다).
 */
@Component
public class TaxSettlementViewPersistenceAdapter implements LoadSettlementForTaxPort {

    private final SpringDataSettlementJpaRepository settlementRepository;

    public TaxSettlementViewPersistenceAdapter(SpringDataSettlementJpaRepository settlementRepository) {
        this.settlementRepository = settlementRepository;
    }

    @Override
    public Optional<TaxSettlementView> findById(Long settlementId) {
        return settlementRepository.findById(settlementId).map(TaxSettlementViewPersistenceAdapter::toView);
    }

    private static TaxSettlementView toView(SettlementJpaEntity e) {
        return new TaxSettlementView(e.getId(), e.getCommission(), e.getNetAmount(),
                e.getSettlementDate(), e.getStatus(), immediatePayoutAmount(e));
    }

    /** Settlement.getImmediatePayoutAmount() 와 동일 규칙(holdbackReleased ? net : max(net−holdback,0)). */
    private static BigDecimal immediatePayoutAmount(SettlementJpaEntity e) {
        if (e.getNetAmount() == null) {
            return BigDecimal.ZERO;
        }
        if (e.isHoldbackReleased()) {
            return e.getNetAmount();
        }
        BigDecimal holdback = e.getHoldbackAmount() != null ? e.getHoldbackAmount() : BigDecimal.ZERO;
        BigDecimal immediate = e.getNetAmount().subtract(holdback);
        return immediate.max(BigDecimal.ZERO);
    }
}
