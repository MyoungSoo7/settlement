package github.lms.lemuel.company.domain;

/**
 * 비교 불가 사유. 정확히 3종만 존재한다 — 신뢰도 플래그(상한 도달)는 실패 사유가 아니므로 여기 없다.
 */
public enum ComparisonUnavailableReason {

    /** 세부·상위 단계 모두 표본이 {@link ComparisonPolicy#MIN_SAMPLE_SIZE} 미만. */
    SAMPLE_TOO_SMALL(ComparisonAxis.INDUSTRY, ComparisonAxis.REGION),

    /** 원본 CSV 업종코드가 공란(사업장 미신고) — 업종 집단을 만들 수 없다. */
    INDUSTRY_CODE_MISSING(ComparisonAxis.INDUSTRY),

    /** 주소에서 시도를 뽑지 못함 — 지역 집단을 만들 수 없다. */
    REGION_UNPARSEABLE(ComparisonAxis.REGION);

    private final java.util.Set<ComparisonAxis> applicableAxes;

    ComparisonUnavailableReason(ComparisonAxis... applicableAxes) {
        this.applicableAxes = java.util.Set.of(applicableAxes);
    }

    public boolean appliesTo(ComparisonAxis axis) {
        return applicableAxes.contains(axis);
    }
}
