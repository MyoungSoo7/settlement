package github.lms.lemuel.insurance.adapter.out.persistence;

import github.lms.lemuel.insurance.application.port.out.LoadProposalPort;
import github.lms.lemuel.insurance.application.port.out.LoadRateTablePort;
import github.lms.lemuel.insurance.application.port.out.SaveProposalPort;
import github.lms.lemuel.insurance.domain.Gender;
import github.lms.lemuel.insurance.domain.ProposalQuote;
import github.lms.lemuel.insurance.domain.ProposalStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class ProposalPersistenceAdapter
        implements LoadProposalPort, SaveProposalPort, LoadRateTablePort {

    private final SpringDataProposalQuoteRepository proposalRepository;
    private final SpringDataPremiumRateTableRepository rateTableRepository;

    public ProposalPersistenceAdapter(
            SpringDataProposalQuoteRepository proposalRepository,
            SpringDataPremiumRateTableRepository rateTableRepository) {
        this.proposalRepository = proposalRepository;
        this.rateTableRepository = rateTableRepository;
    }

    @Override
    public Optional<ProposalQuote> findByProposalId(String proposalId) {
        return proposalRepository.findByProposalId(UUID.fromString(proposalId))
                .map(ProposalQuoteJpaEntity::toDomain);
    }

    @Override
    public List<ProposalQuote> findQuotedValidUntilBefore(LocalDate date) {
        return proposalRepository.findByStatusAndValidUntilBefore(ProposalStatus.QUOTED, date)
                .stream()
                .map(ProposalQuoteJpaEntity::toDomain)
                .toList();
    }

    @Override
    public ProposalQuote insertNew(ProposalQuote proposal) {
        return proposalRepository.saveAndFlush(ProposalQuoteJpaEntity.fromDomain(proposal)).toDomain();
    }

    @Override
    public ProposalQuote update(ProposalQuote proposal) {
        ProposalQuoteJpaEntity entity = proposalRepository.findById(proposal.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "존재하지 않는 가입설계 행에 전이 결과를 반영할 수 없습니다: id=" + proposal.getId()));
        entity.applyTransitionResult(proposal);
        return proposalRepository.saveAndFlush(entity).toDomain();
    }

    @Override
    public Optional<RateSnapshot> findApplicableRate(String productCode, Gender gender,
                                                     int insuranceAge, int paymentTermYears,
                                                     LocalDate asOf) {
        return rateTableRepository
                .findApplicable(productCode, gender.name(), insuranceAge, paymentTermYears, asOf)
                .stream()
                .findFirst()
                .map(PremiumRateTableJpaEntity::toSnapshot);
    }
}
