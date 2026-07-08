package github.lms.lemuel.economics.application.port.out;

import github.lms.lemuel.economics.domain.IndicatorValue;

import java.time.LocalDate;
import java.util.List;

public interface LoadIndicatorValuePort {

    /** 최신 관측치부터 limit 건 (observedDate DESC). 변동 계산엔 limit=2. */
    List<IndicatorValue> findLatest(String indicatorCode, int limit);

    /** [from, to] 시계열, observedDate ASC. */
    List<IndicatorValue> findSeries(String indicatorCode, LocalDate from, LocalDate to);
}
