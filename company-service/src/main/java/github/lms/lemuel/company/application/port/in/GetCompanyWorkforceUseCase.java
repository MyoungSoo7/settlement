package github.lms.lemuel.company.application.port.in;

import github.lms.lemuel.company.domain.CompanyWorkforce;

import java.util.List;

/** 사업장명으로 인원수/추정연봉 조회. */
public interface GetCompanyWorkforceUseCase {

    WorkforcePage search(String workplaceName, int page, int size);

    record WorkforcePage(List<CompanyWorkforce> content, int page, int size, long totalElements) {
        public int totalPages() {
            return size == 0 ? 0 : (int) ((totalElements + size - 1) / size);
        }
    }
}
