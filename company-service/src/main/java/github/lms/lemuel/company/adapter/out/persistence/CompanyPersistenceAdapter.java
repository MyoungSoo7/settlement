package github.lms.lemuel.company.adapter.out.persistence;

import github.lms.lemuel.company.application.port.out.LoadCompanyPort;
import github.lms.lemuel.company.application.port.out.SaveCompanyPort;
import github.lms.lemuel.company.domain.Company;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 기업 마스터 Load + 일괄 upsert 어댑터. 쓰기 경로(SaveCompanyPort)는 외부 유니버스 등록
 * 진입점을 위해 추가됐다 — Flyway 시드 외의 적재원이 생겼다(RegisterCompaniesUseCase).
 */
@Component
public class CompanyPersistenceAdapter implements LoadCompanyPort, SaveCompanyPort {

    private static final Logger log = LoggerFactory.getLogger(CompanyPersistenceAdapter.class);

    private final CompanyRepository repository;

    public CompanyPersistenceAdapter(CompanyRepository repository) {
        this.repository = repository;
    }

    /**
     * 항목별 개별 저장으로 부분 실패를 격리한다 — corpCode UNIQUE 충돌(다른 stockCode 가 이미
     * 그 고유번호를 점유) 은 해당 건만 skip, 나머지는 진행. exists 선체크로 등록/갱신을 구분한다.
     *
     * <p>단일 tx 로 묶지 않는다 — 한 건의 제약 위반이 tx 를 rollback-only 로 오염시켜 나머지를
     * 말아 넣는 것을 막고, repository 호출별 tx 로 부분 실패를 격리한다(ArticlePersistenceAdapter 와 동형).
     */
    @Override
    public UpsertResult upsertAll(List<Company> companies) {
        Instant now = Instant.now();
        int registered = 0;
        int updated = 0;
        int skipped = 0;
        for (Company company : companies) {
            boolean exists = repository.existsById(company.stockCode());
            try {
                repository.saveAndFlush(CompanyJpaEntity.fromDomain(company, now));
                if (exists) {
                    updated++;
                } else {
                    registered++;
                }
            } catch (DataIntegrityViolationException e) {
                log.warn("기업 등록 스킵 stockCode={} — 제약 위반: {}", company.stockCode(), e.getMessage());
                skipped++;
            }
        }
        return new UpsertResult(registered, updated, skipped);
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
