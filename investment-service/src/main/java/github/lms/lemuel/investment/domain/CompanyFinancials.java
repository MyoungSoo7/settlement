package github.lms.lemuel.investment.domain;

import java.util.Comparator;
import java.util.List;

/**
 * 한 상장사의 식별 정보 + 연도별 요약 재무제표 묶음(순수 POJO).
 *
 * <p>{@code statements} 는 회계연도별 최대 1건(어댑터에서 CFS 우선 dedupe)으로 들어오며,
 * {@link #latest()} / {@link #previous()} 로 최신·직전 연도를 얻어 투자점수를 산정한다.
 */
public record CompanyFinancials(
        String stockCode,
        String companyName,
        String market,
        List<AnnualStatement> statements
) {

    /** 재무제표가 하나도 없으면 점수를 낼 수 없다. */
    public boolean hasStatements() {
        return statements != null && !statements.isEmpty();
    }

    private List<AnnualStatement> sortedDesc() {
        return statements.stream()
                .sorted(Comparator.comparingInt(AnnualStatement::fiscalYear).reversed())
                .toList();
    }

    /** 최신 회계연도 재무제표. */
    public AnnualStatement latest() {
        return sortedDesc().get(0);
    }

    /** 최신 직전 연도 재무제표(없으면 null → 성장성 중립 처리). */
    public AnnualStatement previous() {
        int latestYear = latest().fiscalYear();
        return sortedDesc().stream()
                .filter(s -> s.fiscalYear() < latestYear)
                .findFirst()
                .orElse(null);
    }
}
