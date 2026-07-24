package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.application.port.out.LoadLoanPort;
import github.lms.lemuel.loan.application.port.out.SaveLoanPort;
import github.lms.lemuel.loan.domain.LoanAdvance;
import github.lms.lemuel.loan.domain.LoanStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class LoanPersistenceAdapter implements SaveLoanPort, LoadLoanPort {

    private final LoanAdvanceRepository repository;

    public LoanPersistenceAdapter(LoanAdvanceRepository repository) {
        this.repository = repository;
    }

    @Override
    public LoanAdvance save(LoanAdvance loan) {
        LoanAdvanceJpaEntity saved = repository.save(toEntity(loan));
        return toDomain(saved);
    }

    @Override
    public LoanAdvance load(Long loanId) {
        return repository.findById(loanId)
                .map(LoanPersistenceAdapter::toDomain)
                .orElseThrow(() -> new IllegalArgumentException("대출을 찾을 수 없습니다. loanId=" + loanId));
    }

    @Override
    public List<LoanAdvance> findBySeller(Long sellerId) {
        return repository.findBySellerIdOrderByIdAsc(sellerId).stream()
                .map(LoanPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public List<LoanAdvance> findRepayableBySellerForUpdate(Long sellerId) {
        // 상환 recovery 대상: 미상환 잔액이 남은 DISBURSED + OVERDUE(연체 후 회수). FIFO(id asc)로 락 조회.
        return repository.findBySellerAndStatusesForUpdate(
                        sellerId, List.of(LoanStatus.DISBURSED, LoanStatus.OVERDUE)).stream()
                .map(LoanPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public List<LoanAdvance> findOverdueCandidates(LocalDateTime asOf) {
        return repository.findByStatusAndDueAtBefore(LoanStatus.DISBURSED, asOf).stream()
                .map(LoanPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public List<LoanAdvance> findWriteOffCandidates(LocalDateTime asOf) {
        return repository.findByStatusAndDueAtBefore(LoanStatus.OVERDUE, asOf).stream()
                .map(LoanPersistenceAdapter::toDomain)
                .toList();
    }

    private static LoanAdvanceJpaEntity toEntity(LoanAdvance loan) {
        return new LoanAdvanceJpaEntity(
                loan.getId(),
                loan.getSellerId(),
                loan.getPrincipal(),
                loan.getFee(),
                loan.getOutstanding(),
                loan.getStatus(),
                loan.getFinancingDays(),
                loan.getDisbursedAt(),
                loan.getDueAt(),
                LocalDateTime.now());
    }

    private static LoanAdvance toDomain(LoanAdvanceJpaEntity e) {
        return LoanAdvance.reconstitute(
                e.getId(), e.getSellerId(), e.getPrincipal(), e.getFee(),
                e.getOutstanding(), e.getStatus(),
                e.getFinancingDays(), e.getDisbursedAt(), e.getDueAt());
    }
}
