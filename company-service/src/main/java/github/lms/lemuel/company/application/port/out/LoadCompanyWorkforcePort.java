package github.lms.lemuel.company.application.port.out;

import github.lms.lemuel.company.domain.CompanyWorkforce;
import github.lms.lemuel.company.domain.WorkplaceSeriesKey;

import java.util.List;

/** 국민연금 사업장가입자 스냅샷 조회 — 사업장명 텍스트 검색(전국 사업장 전체, Company 와 무관). */
public interface LoadCompanyWorkforcePort {

    SearchResult search(String workplaceName, int page, int size);

    /** 시계열 키(사업장명+앞6자리)의 전 스냅샷 — 정렬은 도메인({@code WorkforceHistory})이 책임진다. */
    List<CompanyWorkforce> findSeries(WorkplaceSeriesKey key);

    record SearchResult(List<CompanyWorkforce> content, long totalElements) {
    }
}
