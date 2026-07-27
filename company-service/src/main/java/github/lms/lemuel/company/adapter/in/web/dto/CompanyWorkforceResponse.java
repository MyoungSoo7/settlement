package github.lms.lemuel.company.adapter.in.web.dto;

import github.lms.lemuel.company.domain.CompanyWorkforce;

import java.math.BigDecimal;

public record CompanyWorkforceResponse(String workplaceName, String industryName, String address, int headcount,
                                        BigDecimal estimatedAnnualSalary, String snapshotMonth, String note) {

    private static final String CAP_DISCLAIMER =
            "국민연금 기준소득월액 상한 적용 추정치입니다 — 실제 급여와 다를 수 있습니다.";

    public static CompanyWorkforceResponse from(CompanyWorkforce workforce) {
        return new CompanyWorkforceResponse(
                workforce.workplaceName(),
                workforce.industryName(),
                workforce.address(),
                workforce.headcount(),
                workforce.estimatedAnnualSalary().orElse(null),
                workforce.snapshotMonth().toString(),
                CAP_DISCLAIMER);
    }
}
