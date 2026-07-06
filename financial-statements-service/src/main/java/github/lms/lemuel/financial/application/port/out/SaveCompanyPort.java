package github.lms.lemuel.financial.application.port.out;

import github.lms.lemuel.financial.domain.Company;

public interface SaveCompanyPort {

    /** 종목코드 기준 upsert — 기존 행이 있으면 corp_code/기업명 갱신. */
    void upsert(Company company);
}
