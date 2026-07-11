package github.lms.lemuel.company.adapter.out.persistence;

import github.lms.lemuel.company.application.port.out.LoadCompanyDocumentPort;
import github.lms.lemuel.company.application.port.out.SaveCompanyDocumentPort;
import github.lms.lemuel.company.domain.CompanyDocument;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Component
public class CompanyDocumentPersistenceAdapter implements LoadCompanyDocumentPort, SaveCompanyDocumentPort {

    private final CompanyDocumentRepository repository;

    public CompanyDocumentPersistenceAdapter(CompanyDocumentRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public CompanyDocument saveOrReplace(CompanyDocument document, byte[] content) {
        CompanyDocumentJpaEntity entity = repository
                .findByStockCodeAndFileName(document.stockCode(), document.fileName())
                .map(existing -> {
                    existing.replaceWith(document, content);
                    return existing;
                })
                .orElseGet(() -> CompanyDocumentJpaEntity.fromDomain(document, content));
        try {
            return repository.save(entity).toDomain();
        } catch (DataIntegrityViolationException e) {
            // (stock_code, file_name) UNIQUE 충돌 — 동시 업로드 레이스. 교체 시맨틱이므로 재시도 안내.
            throw new IllegalStateException("동시 업로드 충돌 — 잠시 후 다시 시도해주세요: "
                    + document.stockCode() + "/" + document.fileName(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<CompanyDocument> findByStockCode(String stockCode) {
        return repository.findMetaByStockCode(stockCode).stream()
                .map(CompanyDocumentMeta::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DocumentContent> findWithContent(Long id) {
        return repository.findById(id)
                .map(entity -> new DocumentContent(entity.toDomain(), entity.content()));
    }
}
