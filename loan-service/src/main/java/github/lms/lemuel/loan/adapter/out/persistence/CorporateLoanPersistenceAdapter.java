package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.application.port.out.LoadCorporateLoanPort;
import github.lms.lemuel.loan.application.port.out.SaveCorporateLoanPort;
import github.lms.lemuel.loan.domain.CorporateLoan;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
public class CorporateLoanPersistenceAdapter implements SaveCorporateLoanPort, LoadCorporateLoanPort {

    private final CorporateLoanRepository repository;

    public CorporateLoanPersistenceAdapter(CorporateLoanRepository repository) {
        this.repository = repository;
    }

    @Override
    public CorporateLoan save(CorporateLoan loan) {
        return toDomain(repository.save(toEntity(loan)));
    }

    @Override
    public Optional<CorporateLoan> findById(Long loanId) {
        return repository.findById(loanId).map(CorporateLoanPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<CorporateLoan> findByIdForUpdate(Long loanId) {
        return repository.findByIdForUpdate(loanId).map(CorporateLoanPersistenceAdapter::toDomain);
    }

    @Override
    public List<CorporateLoan> findByStockCode(String stockCode) {
        return repository.findByStockCodeOrderByIdDesc(stockCode).stream()
                .map(CorporateLoanPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public List<CorporateLoan> findRecent(int limit) {
        return repository.findAllByOrderByIdDesc(PageRequest.of(0, limit)).stream()
                .map(CorporateLoanPersistenceAdapter::toDomain)
                .toList();
    }

    private static CorporateLoanJpaEntity toEntity(CorporateLoan loan) {
        return new CorporateLoanJpaEntity(
                loan.getId(),
                loan.getStockCode(),
                loan.getCorpName(),
                loan.getPrincipal(),
                loan.getFee(),
                loan.getOutstanding(),
                loan.getTermDays(),
                loan.getCreditScore(),
                loan.getCreditGrade(),
                loan.getStatus(),
                loan.getCreatedAt() != null ? loan.getCreatedAt() : LocalDateTime.now());
    }

    private static CorporateLoan toDomain(CorporateLoanJpaEntity e) {
        return CorporateLoan.reconstitute(
                e.getId(), e.getStockCode(), e.getCorpName(), e.getPrincipal(), e.getFee(),
                e.getOutstanding(), e.getTermDays(), e.getCreditScore(), e.getCreditGrade(),
                e.getStatus(), e.getCreatedAt());
    }
}
