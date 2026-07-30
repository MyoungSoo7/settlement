package github.lms.lemuel.company.domain;

/**
 * 한 비교축(업종 또는 지역)의 판정 결과. 두 지표는 같은 집단을 공유하므로 집단 정보
 * (단계·집단키·표본수·사유)는 축마다 하나뿐이고, 지표별 결과만 둘로 갈린다.
 *
 * <p>비교가 불가능해도 이 객체 자체는 항상 존재한다 — 사유 코드를 실어 보내야 하기 때문이다.
 * null 이 되는 것은 지표 하위 객체({@link #headcount()} / {@link #estimatedAnnualSalary()})다.
 */
public record GroupComparison(ComparisonAxis axis, ComparisonLevel level, String groupKey, int sampleSize,
                              ComparisonUnavailableReason unavailableReason,
                              MetricComparison headcount, MetricComparison estimatedAnnualSalary) {

    public GroupComparison {
        if (axis == null) {
            throw new IllegalArgumentException("비교축은 필수입니다");
        }
        if (unavailableReason != null && !unavailableReason.appliesTo(axis)) {
            throw new IllegalArgumentException("비교축에 맞지 않는 사유 코드입니다: " + axis + " / " + unavailableReason);
        }
        if (unavailableReason != null && (headcount != null || estimatedAnnualSalary != null)) {
            throw new IllegalArgumentException("비교 불가 사유가 있으면 지표 비교 결과를 담을 수 없습니다: " + axis);
        }
        if (unavailableReason == null && !ComparisonPolicy.hasEnoughSample(sampleSize)) {
            throw new IllegalArgumentException(
                    "표본 " + sampleSize + "건으로 비교 성립을 주장할 수 없습니다(최소 "
                            + ComparisonPolicy.MIN_SAMPLE_SIZE + "건): " + axis);
        }
        if (unavailableReason == null && (level == null || groupKey == null)) {
            throw new IllegalArgumentException("비교가 성립하면 적용된 단계와 집단 키가 있어야 합니다: " + axis);
        }
    }

    /**
     * 비교 성립. 지표 하위 객체는 대상 사업장 값이나 사전 계산된 백분위가 없으면 개별적으로 null 일 수
     * 있다(집단은 성립했으므로 사유 코드는 붙지 않는다).
     */
    public static GroupComparison available(ComparisonAxis axis, ComparisonLevel level, String groupKey,
                                            int sampleSize, MetricComparison headcount,
                                            MetricComparison estimatedAnnualSalary) {
        return new GroupComparison(axis, level, groupKey, sampleSize, null, headcount, estimatedAnnualSalary);
    }

    /**
     * 세부·상위 단계 모두 표본 미달. 마지막으로 시도한 단계·집단키·표본수를 그대로 실어 보낸다 —
     * 사용자가 "얼마나 모자랐는지"를 해석할 수 있어야 한다.
     */
    public static GroupComparison sampleTooSmall(ComparisonAxis axis, ComparisonLevel attemptedLevel,
                                                 String attemptedGroupKey, int sampleSize) {
        return new GroupComparison(axis, attemptedLevel, attemptedGroupKey, sampleSize,
                ComparisonUnavailableReason.SAMPLE_TOO_SMALL, null, null);
    }

    /** 집단 자체를 만들 수 없음(업종코드 공란·주소 파싱 실패) — 단계·집단키가 없다. */
    public static GroupComparison noGroup(ComparisonAxis axis, ComparisonUnavailableReason reason) {
        if (reason == null) {
            throw new IllegalArgumentException("비교 불가 사유는 필수입니다: " + axis);
        }
        return new GroupComparison(axis, null, null, 0, reason, null, null);
    }

    public boolean isAvailable() {
        return unavailableReason == null;
    }
}
