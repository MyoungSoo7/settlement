package github.lms.lemuel.company.adapter.out.persistence;

import github.lms.lemuel.company.application.port.out.LoadCompanyWorkforcePort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CompanyWorkforcePersistenceAdapter implements LoadCompanyWorkforcePort {

    private final CompanyWorkforceRepository repository;

    public CompanyWorkforcePersistenceAdapter(CompanyWorkforceRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public SearchResult search(String workplaceName, int page, int size) {
        Page<CompanyWorkforceJpaEntity> result = workplaceName == null
                ? repository.findAll(PageRequest.of(page, size, Sort.by("workplaceName").ascending()))
                : repository.search(workplaceName, PageRequest.of(page, size));
        return new SearchResult(result.map(CompanyWorkforceJpaEntity::toDomain).getContent(), result.getTotalElements());
    }
}
