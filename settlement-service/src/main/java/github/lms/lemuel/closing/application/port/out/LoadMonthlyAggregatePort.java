package github.lms.lemuel.closing.application.port.out;

import github.lms.lemuel.closing.application.dto.MonthlyAggregateSnapshot;

import java.time.YearMonth;

/** 대상 월의 셀러별 DONE 정산 집계(+미매핑·미확정 카운트)를 읽는다. */
public interface LoadMonthlyAggregatePort {

    MonthlyAggregateSnapshot load(YearMonth period);
}
