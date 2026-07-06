package github.lms.lemuel.economics.application.port.out;

import github.lms.lemuel.economics.domain.Indicator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface EcosClientPort {

    boolean isConfigured();

    /** 지표의 [from, to] 관측치 조회. 결측/휴장일은 응답에 없을 뿐 — 에러 아님. */
    List<Observation> fetchObservations(Indicator indicator, LocalDate from, LocalDate to);

    record Observation(LocalDate observedDate, BigDecimal value) { }
}
