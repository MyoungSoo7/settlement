package github.lms.lemuel.company.domain;

/**
 * 사업장 1건의 상세 비교 결과 = 대상 스냅샷 + 업종축 판정 + 지역축 판정.
 *
 * <p>두 축은 서로의 성패에 영향을 주지 않는다 — 업종코드가 없어도 지역 비교는 정상 제공되고, 그 반대도
 * 같다. 상한 도달 신뢰도 플래그도 두 축의 성패와 무관하게 항상 제공된다({@link CompanyWorkforce}).
 */
public record WorkforceComparison(CompanyWorkforce workforce, GroupComparison industryComparison,
                                  GroupComparison regionComparison) {

    public WorkforceComparison {
        if (workforce == null) {
            throw new IllegalArgumentException("대상 사업장은 필수입니다");
        }
        if (industryComparison == null || industryComparison.axis() != ComparisonAxis.INDUSTRY) {
            throw new IllegalArgumentException("업종축 비교 결과가 필요합니다");
        }
        if (regionComparison == null || regionComparison.axis() != ComparisonAxis.REGION) {
            throw new IllegalArgumentException("지역축 비교 결과가 필요합니다");
        }
    }
}
