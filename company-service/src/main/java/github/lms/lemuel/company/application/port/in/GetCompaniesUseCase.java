package github.lms.lemuel.company.application.port.in;

import github.lms.lemuel.company.domain.Company;

import java.util.List;
import java.util.Optional;

/** 기업 목록/검색·단건 조회. */
public interface GetCompaniesUseCase {

    CompanyPage search(String keyword, int page, int size);

    Optional<Company> byStockCode(String stockCode);

    record CompanyPage(List<Company> content, int page, int size, long totalElements) {
        public int totalPages() {
            return size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        }
    }
}
