package github.lms.lemuel.economics.application.port.in;

import github.lms.lemuel.economics.domain.Indicator;
import github.lms.lemuel.economics.domain.IndicatorValue;

import java.util.List;

public interface GetIndicatorsUseCase {

    /** 카탈로그 전체 + 각 지표의 최신 관측치/전기 대비 변동(관측치 부족 시 null). */
    List<IndicatorSnapshot> getIndicators();

    IndicatorSnapshot getIndicator(String code);

    record IndicatorSnapshot(Indicator indicator, IndicatorValue latest, IndicatorValue.Change change) { }
}
