package github.lms.lemuel.company.domain;

/**
 * 월별 집계 빌드의 적재 대사 카운트. 원본 CSV 행 수는 수용·거부로 정확히 갈라져야 한다 —
 * {@code sourceRowCount = acceptedRowCount + rejectedRowCount} 가 깨지면 집계 모집단의 근거가 사라진다.
 *
 * <p>수용 행만 중앙값·백분위 모집단이 된다(거부 행은 저장되지 않으므로 애초에 모집단 밖이다).
 */
public record AggregateRowTally(long sourceRowCount, long acceptedRowCount, long rejectedRowCount) {

    public AggregateRowTally {
        if (sourceRowCount < 0 || acceptedRowCount < 0 || rejectedRowCount < 0) {
            throw new IllegalArgumentException("적재 대사 카운트는 음수일 수 없습니다");
        }
        if (sourceRowCount != acceptedRowCount + rejectedRowCount) {
            throw new IllegalArgumentException("적재 대사 불일치 — 원본 " + sourceRowCount
                    + " ≠ 수용 " + acceptedRowCount + " + 거부 " + rejectedRowCount);
        }
    }
}
