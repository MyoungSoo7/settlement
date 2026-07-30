package github.lms.lemuel.company.adapter.in.web.dto;

import github.lms.lemuel.company.domain.CompanyWorkforce;

import java.math.BigDecimal;

/**
 * 목록 검색 1행. {@code bizRegNoPrefix} 는 상세({@code /detail}) 복합키 3요소를 목록만으로 채우기 위한
 * 추가 필드다(2026-07-30 프런트 배선에서 필요해짐 — 기존 필드는 불변, 하위호환 확장).
 */
public record CompanyWorkforceResponse(String workplaceName, String bizRegNoPrefix, String industryName,
                                        String address, int headcount,
                                        BigDecimal estimatedAnnualSalary, String snapshotMonth, String note) {

    private static final String CAP_DISCLAIMER =
            "국민연금 기준소득월액 상한 적용 추정치입니다 — 실제 급여와 다를 수 있습니다.";

    public static CompanyWorkforceResponse from(CompanyWorkforce workforce) {
        return new CompanyWorkforceResponse(
                workforce.workplaceName(),
                workforce.bizRegNoPrefix(),
                workforce.industryName(),
                workforce.address(),
                workforce.headcount(),
                workforce.estimatedAnnualSalary().orElse(null),
                workforce.snapshotMonth().toString(),
                CAP_DISCLAIMER);
    }
}
