package github.lms.lemuel.economics.application.service;

import github.lms.lemuel.economics.application.port.in.GetIndicatorsUseCase.IndicatorSnapshot;
import github.lms.lemuel.economics.application.port.out.LoadIndicatorPort;
import github.lms.lemuel.economics.application.port.out.LoadIndicatorValuePort;
import github.lms.lemuel.economics.domain.Indicator;
import github.lms.lemuel.economics.domain.IndicatorCycle;
import github.lms.lemuel.economics.domain.IndicatorNotFoundException;
import github.lms.lemuel.economics.domain.IndicatorValue;
import github.lms.lemuel.economics.domain.ValueSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndicatorQueryServiceTest {

    @Mock
    private LoadIndicatorPort loadIndicatorPort;
    @Mock
    private LoadIndicatorValuePort loadIndicatorValuePort;

    @InjectMocks
    private IndicatorQueryService service;

    private final Indicator baseRate = new Indicator("BASE_RATE", "한국은행 기준금리", "%",
            IndicatorCycle.D, "722Y001", "0101000", null);
    private final Indicator cpi = new Indicator("CPI", "소비자물가지수", "2020=100",
            IndicatorCycle.M, "901Y009", "0", null);

    private IndicatorValue value(String code, String v, LocalDate d) {
        return new IndicatorValue(null, code, d, new BigDecimal(v), ValueSource.SEED, null);
    }

    @Test
    @DisplayName("getIndicators — 지표별 findLatest(code,2)로 최신값+전기 대비 변동 조립 (지표당 1콜, N+1 허용)")
    void getIndicators_buildsSnapshots() {
        when(loadIndicatorPort.findAll()).thenReturn(List.of(baseRate, cpi));
        // findLatest 는 observedDate DESC — [최신, 직전]
        when(loadIndicatorValuePort.findLatest("BASE_RATE", 2)).thenReturn(List.of(
                value("BASE_RATE", "3.25", LocalDate.of(2026, 6, 1)),
                value("BASE_RATE", "3.00", LocalDate.of(2026, 5, 1))));
        when(loadIndicatorValuePort.findLatest("CPI", 2)).thenReturn(List.of(
                value("CPI", "114.5", LocalDate.of(2026, 6, 1))));  // 관측치 1건 → change null

        List<IndicatorSnapshot> result = service.getIndicators();

        assertThat(result).hasSize(2);

        IndicatorSnapshot base = result.get(0);
        assertThat(base.indicator()).isEqualTo(baseRate);
        assertThat(base.latest().value()).isEqualByComparingTo("3.25");
        assertThat(base.change().amount()).isEqualByComparingTo("0.25");

        IndicatorSnapshot cpiSnap = result.get(1);
        assertThat(cpiSnap.latest().value()).isEqualByComparingTo("114.5");
        assertThat(cpiSnap.change()).isNull();  // 관측치 1건이라 변동 계산 불가

        verify(loadIndicatorValuePort).findLatest("BASE_RATE", 2);
        verify(loadIndicatorValuePort).findLatest("CPI", 2);
    }

    @Test
    @DisplayName("getIndicators — 관측치 0건 지표는 latest·change 모두 null")
    void getIndicators_zeroObservations() {
        when(loadIndicatorPort.findAll()).thenReturn(List.of(baseRate));
        when(loadIndicatorValuePort.findLatest("BASE_RATE", 2)).thenReturn(List.of());

        List<IndicatorSnapshot> result = service.getIndicators();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).latest()).isNull();
        assertThat(result.get(0).change()).isNull();
    }

    @Test
    @DisplayName("getIndicator — 단건 스냅샷 (최신값+변동)")
    void getIndicator_single() {
        when(loadIndicatorPort.findByCode("BASE_RATE")).thenReturn(Optional.of(baseRate));
        when(loadIndicatorValuePort.findLatest("BASE_RATE", 2)).thenReturn(List.of(
                value("BASE_RATE", "3.25", LocalDate.of(2026, 6, 1)),
                value("BASE_RATE", "3.00", LocalDate.of(2026, 5, 1))));

        IndicatorSnapshot snap = service.getIndicator("BASE_RATE");

        assertThat(snap.indicator()).isEqualTo(baseRate);
        assertThat(snap.latest().value()).isEqualByComparingTo("3.25");
        assertThat(snap.change().amount()).isEqualByComparingTo("0.25");
    }

    @Test
    @DisplayName("getIndicator — 없는 code 는 IndicatorNotFoundException")
    void getIndicator_unknownCode() {
        when(loadIndicatorPort.findByCode("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getIndicator("NOPE"))
                .isInstanceOf(IndicatorNotFoundException.class)
                .hasMessageContaining("NOPE");
    }

    @Test
    @DisplayName("getSeries — 없는 code 는 IndicatorNotFoundException (조회 전 존재검증)")
    void getSeries_unknownCode() {
        when(loadIndicatorPort.findByCode("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSeries("NOPE", null, null))
                .isInstanceOf(IndicatorNotFoundException.class)
                .hasMessageContaining("NOPE");
    }

    @Test
    @DisplayName("getSeries — from > to 는 IllegalArgumentException")
    void getSeries_invertedRange() {
        when(loadIndicatorPort.findByCode("BASE_RATE")).thenReturn(Optional.of(baseRate));

        assertThatIllegalArgumentException().isThrownBy(() ->
                service.getSeries("BASE_RATE", LocalDate.of(2026, 6, 30), LocalDate.of(2026, 1, 1)));
    }

    @Test
    @DisplayName("getSeries — from/to null 이면 최근 1년으로 보정해 포트 호출")
    void getSeries_nullRangeDefaultsToOneYear() {
        when(loadIndicatorPort.findByCode("BASE_RATE")).thenReturn(Optional.of(baseRate));
        List<IndicatorValue> series = List.of(value("BASE_RATE", "3.00", LocalDate.of(2026, 1, 1)));
        ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> toCaptor = ArgumentCaptor.forClass(LocalDate.class);
        when(loadIndicatorValuePort.findSeries(eq("BASE_RATE"), fromCaptor.capture(), toCaptor.capture()))
                .thenReturn(series);

        List<IndicatorValue> result = service.getSeries("BASE_RATE", null, null);

        assertThat(result).isEqualTo(series);
        LocalDate from = fromCaptor.getValue();
        LocalDate to = toCaptor.getValue();
        assertThat(to).isNotNull();
        assertThat(from).isEqualTo(to.minusYears(1));  // 최근 1년
    }
}
