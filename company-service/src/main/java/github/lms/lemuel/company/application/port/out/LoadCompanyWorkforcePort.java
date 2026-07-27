package github.lms.lemuel.company.application.port.out;

import github.lms.lemuel.company.domain.CompanyWorkforce;

import java.util.List;

/** 국민연금 사업장가입자 스냅샷 조회 — 사업장명 텍스트 검색(전국 사업장 전체, Company 와 무관). */
public interface LoadCompanyWorkforcePort {

    SearchResult search(String workplaceName, int page, int size);

    record SearchResult(List<CompanyWorkforce> content, long totalElements) {
    }
}
