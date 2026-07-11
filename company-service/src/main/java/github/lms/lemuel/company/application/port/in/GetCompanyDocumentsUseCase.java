package github.lms.lemuel.company.application.port.in;

import github.lms.lemuel.company.domain.CompanyDocument;

import java.util.List;
import java.util.Optional;

public interface GetCompanyDocumentsUseCase {

    /** 기업별 문서 메타데이터 목록 (최신 업로드 순, 파일 바이트 미포함). */
    List<CompanyDocument> byCompany(String stockCode);

    Optional<DocumentDownload> download(Long id);

    record DocumentDownload(CompanyDocument document, byte[] content) {
    }
}
