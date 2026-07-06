package github.lms.lemuel.company.application.port.out;

import github.lms.lemuel.company.domain.Company;

import java.util.List;
import java.util.Optional;

public interface LoadCompanyPort {

    SearchResult search(String keyword, int page, int size);

    Optional<Company> findByStockCode(String stockCode);

    List<Company> findAll();

    record SearchResult(List<Company> content, long totalElements) {
    }
}
