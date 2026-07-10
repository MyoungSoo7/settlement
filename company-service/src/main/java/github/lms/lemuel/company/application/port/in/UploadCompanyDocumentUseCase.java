package github.lms.lemuel.company.application.port.in;

import github.lms.lemuel.company.domain.CompanyDocument;

public interface UploadCompanyDocumentUseCase {

    /** 같은 (stockCode, fileName) 이 이미 있으면 내용을 교체한다. */
    CompanyDocument upload(UploadCommand command);

    record UploadCommand(String stockCode, String title, String fileName, byte[] content) {
    }
}
