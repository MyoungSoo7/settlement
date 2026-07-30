package github.lms.lemuel.company.domain;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 사업장 월별 시계열 — 같은 사업장 키({@link WorkplaceSeriesKey})의 스냅샷들을 월 오름차순으로
 * 정렬하고, 연속 인접 월에만 전월 대비 증감을 계산한다.
 *
 * <p>불변식: 시리즈의 모든 스냅샷은 <b>단일 사업장 키</b>여야 하고(AC-3 — 명칭이 다르면 다른
 * 시리즈다), 같은 월이 중복될 수 없다(DB UNIQUE 와 동일 계약을 도메인이 재천명). 결측 월은
 * 보간하지 않는다 — 갭 뒤 지점의 증감은 null 이다.
 */
public final class WorkforceHistory {

    private final List<WorkforceTrendPoint> points;

    private WorkforceHistory(List<WorkforceTrendPoint> points) {
        this.points = List.copyOf(points);
    }

    public static WorkforceHistory of(List<CompanyWorkforce> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            throw new IllegalArgumentException("시계열 스냅샷은 비어 있을 수 없습니다");
        }
        List<CompanyWorkforce> sorted = snapshots.stream()
                .sorted(Comparator.comparing(CompanyWorkforce::snapshotMonth))
                .toList();
        requireSingleSeriesKey(sorted);
        requireDistinctMonths(sorted);

        List<WorkforceTrendPoint> points = new ArrayList<>(sorted.size());
        CompanyWorkforce previous = null;
        for (CompanyWorkforce snapshot : sorted) {
            points.add(isAdjacent(previous, snapshot)
                    ? WorkforceTrendPoint.withChange(snapshot, previous)
                    : WorkforceTrendPoint.withoutChange(snapshot));
            previous = snapshot;
        }
        return new WorkforceHistory(points);
    }

    private static void requireSingleSeriesKey(List<CompanyWorkforce> sorted) {
        CompanyWorkforce first = sorted.get(0);
        boolean mixed = sorted.stream().anyMatch(s ->
                !s.workplaceName().equals(first.workplaceName())
                        || !s.bizRegNoPrefix().equals(first.bizRegNoPrefix()));
        if (mixed) {
            throw new IllegalArgumentException("시계열은 단일 사업장 키의 스냅샷만 담을 수 있습니다");
        }
    }

    private static void requireDistinctMonths(List<CompanyWorkforce> sorted) {
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).snapshotMonth().equals(sorted.get(i - 1).snapshotMonth())) {
                throw new IllegalArgumentException(
                        "같은 기준월이 중복될 수 없습니다: " + sorted.get(i).snapshotMonth());
            }
        }
    }

    /** 연속 인접 월(전월+1) 판정 — 결측 갭이면 거짓이라 증감을 계산하지 않는다. */
    private static boolean isAdjacent(CompanyWorkforce previous, CompanyWorkforce current) {
        if (previous == null) {
            return false;
        }
        YearMonth expected = previous.snapshotMonth().plusMonths(1);
        return expected.equals(current.snapshotMonth());
    }

    public List<WorkforceTrendPoint> points() {
        return points;
    }
}
