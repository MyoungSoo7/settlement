package github.lms.lemuel.economics.application.port.in;

import github.lms.lemuel.economics.domain.IndicatorValue;

import java.time.LocalDate;
import java.util.List;

public interface GetIndicatorSeriesUseCase {

    /** from/to null 이면 최근 1년. observedDate ASC. */
    List<IndicatorValue> getSeries(String code, LocalDate from, LocalDate to);
}
