package github.lms.lemuel.payout.adapter.out.persistence;

import github.lms.lemuel.payout.application.port.out.LoadPayoutPort;
import github.lms.lemuel.payout.application.port.out.SavePayoutPort;
import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutStatus;
import github.lms.lemuel.payout.domain.SellerBankAccount;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Component
public class PayoutPersistenceAdapter implements LoadPayoutPort, SavePayoutPort {

    private final SpringDataPayoutRepository repository;

    public PayoutPersistenceAdapter(SpringDataPayoutRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Payout> findById(Long id) {
        return repository.findById(id).map(PayoutPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<Payout> findBySettlementId(Long settlementId) {
        return repository.findBySettlementId(settlementId).map(PayoutPersistenceAdapter::toDomain);
    }

    @Override
    public List<Payout> findByStatus(PayoutStatus status, int limit) {
        return repository.findByStatusOrderByRequestedAtAsc(status, PageRequest.of(0, Math.max(1, limit)))
                .stream().map(PayoutPersistenceAdapter::toDomain).toList();
    }

    @Override
    public BigDecimal sumCompletedBySellerOn(Long sellerId, LocalDate date) {
        LocalDateTime from = date.atStartOfDay();
        LocalDateTime to = date.plusDays(1).atStartOfDay();
        return repository.sumCompletedBySellerBetween(sellerId, from, to);
    }

    @Override
    public BigDecimal sumCompletedSystemwideOn(LocalDate date) {
        return repository.sumCompletedBetween(date.atStartOfDay(), date.plusDays(1).atStartOfDay());
    }

    @Override
    public Payout save(Payout payout) {
        PayoutJpaEntity entity;
        if (payout.getId() == null) {
            entity = new PayoutJpaEntity(
                    null, payout.getSettlementId(), payout.getSellerId(), payout.getAmount(),
                    payout.getAccount().bankCode(), payout.getAccount().bankAccountNumber(),
                    payout.getAccount().accountHolderName(),
                    payout.getStatus(), payout.getFirmBankingTransactionId(), payout.getFailureReason(),
                    payout.getRetryCount(), payout.getOperatorId(),
                    payout.getRequestedAt(), payout.getSentAt(), payout.getCompletedAt(),
                    payout.getFailedAt(), payout.getCreatedAt(), payout.getUpdatedAt()
            );
        } else {
            entity = repository.findById(payout.getId())
                    .orElseThrow(() -> new IllegalStateException("Payout not found: " + payout.getId()));
            entity.applyDomainState(
                    payout.getStatus(), payout.getFirmBankingTransactionId(), payout.getFailureReason(),
                    payout.getRetryCount(), payout.getOperatorId(),
                    payout.getSentAt(), payout.getCompletedAt(), payout.getFailedAt(),
                    payout.getUpdatedAt()
            );
        }
        return toDomain(repository.save(entity));
    }

    @Override
    public Optional<Payout> claimForSending(Long payoutId) {
        int claimed = repository.claimForSending(payoutId, LocalDateTime.now());
        if (claimed == 0) {
            return Optional.empty();
        }
        // clearAutomatically=true 로 영속성 컨텍스트가 비워졌으므로 DB 에서 SENDING 상태를 다시 읽어 반환.
        return repository.findById(payoutId).map(PayoutPersistenceAdapter::toDomain);
    }

    private static Payout toDomain(PayoutJpaEntity e) {
        SellerBankAccount account = new SellerBankAccount(
                e.getBankCode(), e.getBankAccountNumber(), e.getAccountHolderName()
        );
        return Payout.rehydrate(
                e.getId(), e.getSettlementId(), e.getSellerId(), e.getAmount(), account,
                e.getStatus(), e.getFirmBankingTransactionId(), e.getFailureReason(),
                e.getRetryCount(), e.getOperatorId(),
                e.getRequestedAt(), e.getSentAt(), e.getCompletedAt(), e.getFailedAt(),
                e.getCreatedAt(), e.getUpdatedAt()
        );
    }
}
