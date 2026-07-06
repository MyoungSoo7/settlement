package github.lms.lemuel.financial.application.port.out;

import github.lms.lemuel.financial.domain.Company;

import java.util.List;
import java.util.Optional;

public interface LoadCompanyPort {

    SearchResult search(String keyword, int page, int size);

    Optional<Company> findByStockCode(String stockCode);

    /** DART 재무제표 수집 대상 — 고유번호(corp_code)를 보유한 기업 전체. */
    List<Company> findAllWithCorpCode();

    record SearchResult(List<Company> content, long totalElements) {
    }
}
