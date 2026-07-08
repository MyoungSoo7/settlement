package github.lms.lemuel.company.adapter.in.web.dto;

import github.lms.lemuel.company.domain.Company;

public record CompanyResponse(String stockCode, String corpCode, String name, String market) {

    public static CompanyResponse from(Company company) {
        return new CompanyResponse(company.stockCode(), company.corpCode(), company.name(), company.market());
    }
}
