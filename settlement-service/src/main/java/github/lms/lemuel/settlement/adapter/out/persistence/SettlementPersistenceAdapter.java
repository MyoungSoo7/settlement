package github.lms.lemuel.settlement.adapter.out.persistence;

import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Settlement Persistence Adapter
 */
@Repository
@RequiredArgsConstructor
public class SettlementPersistenceAdapter implements LoadSettlementPort, SaveSettlementPort {

    private final SpringDataSettlementJpaRepository settlementJpaRepository;
    private final SettlementPersistenceMapper mapper;

    @Override
    public Optional<Settlement> findById(Long settlementId) {
        return settlementJpaRepository.findById(settlementId)
                .map(mapper::toDomain);
    }

    @Override
    public Optional<Settlement> findByPaymentId(Long paymentId) {
        return settlementJpaRepository.findByPaymentId(paymentId)
                .map(mapper::toDomain);
    }

    @Override
    public List<Settlement> findBySettlementDate(LocalDate settlementDate) {
        return settlementJpaRepository.findBySettlementDate(settlementDate)
                .stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<Settlement> findBySettlementDateAndStatus(LocalDate settlementDate, SettlementStatus status) {
        return settlementJpaRepository.findBySettlementDateAndStatus(settlementDate, status.name())
                .stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public Settlement save(Settlement settlement) {
        SettlementJpaEntity entity = mapper.toEntity(settlement);
        SettlementJpaEntity saved = settlementJpaRepository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public List<Settlement> saveAll(List<Settlement> settlements) {
        List<SettlementJpaEntity> entities = settlements.stream()
                .map(mapper::toEntity)
                .collect(Collectors.toList());

        List<SettlementJpaEntity> saved = settlementJpaRepository.saveAll(entities);

        return saved.stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }
}
