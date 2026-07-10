package github.lms.lemuel.company.application.port.out;

import github.lms.lemuel.company.domain.CompanyDocument;

import java.util.List;
import java.util.Optional;

public interface LoadCompanyDocumentPort {

    /** 메타데이터만 조회 (파일 바이트 미포함 — 목록에서 BYTEA 를 끌어오지 않는다). */
    List<CompanyDocument> findByStockCode(String stockCode);

    Optional<DocumentContent> findWithContent(Long id);

    record DocumentContent(CompanyDocument document, byte[] content) {
    }
}
