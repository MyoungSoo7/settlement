package github.lms.lemuel.economics.application.port.out;

import github.lms.lemuel.economics.domain.IndicatorValue;

public interface SaveIndicatorValuePort {

    /** (indicator_code, observed_date) UNIQUE upsert — SEED 를 ECOS 가 덮어쓴다. */
    void upsert(IndicatorValue value);
}
