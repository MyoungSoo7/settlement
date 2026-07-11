package github.lms.lemuel.company.application.port.out;

import github.lms.lemuel.company.domain.CompanyDocument;

public interface SaveCompanyDocumentPort {

    /** (stockCode, fileName) 이 이미 있으면 내용·제목을 교체하고, 없으면 새로 저장한다. */
    CompanyDocument saveOrReplace(CompanyDocument document, byte[] content);
}
