package github.lms.lemuel.financial.adapter.out.persistence;

import github.lms.lemuel.financial.application.port.out.LoadCompanyPort;
import github.lms.lemuel.financial.application.port.out.SaveCompanyPort;
import github.lms.lemuel.financial.domain.Company;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Component
public class CompanyPersistenceAdapter implements LoadCompanyPort, SaveCompanyPort {

    private final CompanyRepository repository;

    public CompanyPersistenceAdapter(CompanyRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public SearchResult search(String keyword, int page, int size) {
        Page<CompanyJpaEntity> result = keyword == null
                ? repository.findAll(PageRequest.of(page, size, Sort.by("name").ascending()))
                : repository.search(keyword, PageRequest.of(page, size));
        return new SearchResult(result.map(CompanyJpaEntity::toDomain).getContent(), result.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Company> findByStockCode(String stockCode) {
        return repository.findById(stockCode).map(CompanyJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Company> findAllWithCorpCode() {
        return repository.findByCorpCodeIsNotNull().stream()
                .map(CompanyJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void upsert(Company company) {
        CompanyJpaEntity entity = repository.findById(company.stockCode())
                .map(existing -> {
                    existing.applyDomain(company);
                    return existing;
                })
                .orElseGet(() -> CompanyJpaEntity.fromDomain(company));
        repository.save(entity);
    }
}
