package github.lms.lemuel.financial.adapter.in.web.dto;

import github.lms.lemuel.financial.domain.Company;

public record CompanyResponse(String stockCode, String corpCode, String name, String market) {

    public static CompanyResponse from(Company company) {
        return new CompanyResponse(company.stockCode(), company.corpCode(), company.name(), company.market());
    }
}
