package github.lms.lemuel.company.application.port.out;

import github.lms.lemuel.company.domain.AggregateRowTally;

import java.time.YearMonth;

/**
 * 월별 사전 집계 생성 포트. 조회 경로가 읽는 유일한 집계 원천을 만든다.
 */
public interface BuildWorkforceAggregatePort {

    /**
     * 해당 기준월의 집단 중앙값·표본수와 사업장별 백분위를 <b>하나의 원자적 교체</b>로 통째 갱신한다.
     *
     * <p>중간에 실패하면 직전 COMPLETE 집계가 그대로 남는다(부분 갱신·stale BUILDING 없음).
     */
    void rebuild(YearMonth snapshotMonth, AggregateRowTally tally);
}
