package github.lms.lemuel.company.adapter.out.persistence;

import github.lms.lemuel.company.application.port.out.LoadCompanyPort;
import github.lms.lemuel.company.domain.Company;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Phase 1 은 기업 마스터에 쓰기 경로가 없다 — Flyway 시드가 유일한 적재원이라 Load 포트만
 * 구현한다(마스터 동기화는 ADR 0023 Phase 3+ 범위).
 */
@Component
public class CompanyPersistenceAdapter implements LoadCompanyPort {

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
    public List<Company> findAll() {
        return repository.findAll(Sort.by("stockCode").ascending()).stream()
                .map(CompanyJpaEntity::toDomain)
                .toList();
    }
}
