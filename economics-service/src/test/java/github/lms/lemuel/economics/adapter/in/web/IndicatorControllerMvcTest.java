package github.lms.lemuel.economics.adapter.in.web;

import github.lms.lemuel.economics.application.port.in.GetIndicatorSeriesUseCase;
import github.lms.lemuel.economics.application.port.in.GetIndicatorsUseCase;
import github.lms.lemuel.economics.application.port.in.GetIndicatorsUseCase.IndicatorSnapshot;
import github.lms.lemuel.economics.domain.Indicator;
import github.lms.lemuel.economics.domain.IndicatorCycle;
import github.lms.lemuel.economics.domain.IndicatorNotFoundException;
import github.lms.lemuel.economics.domain.IndicatorValue;
import github.lms.lemuel.economics.domain.ValueSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 프로덕션 Boot 자동구성 Jackson 직렬화 계약을 검증하는 슬라이스 테스트.
 *
 * <p>손수 만든 컨버터를 쓰는 {@link IndicatorControllerTest}(standalone) 와 달리, 여기서는 실제
 * Boot ObjectMapper(JavaTimeModule 자동등록, WRITE_DATES_AS_TIMESTAMPS=false)로 직렬화된다 —
 * 프론트 계약(날짜=ISO 문자열, 수치=JSON number, 결측=null)이 CI 에서 회귀하면 잡힌다.
 * 보안은 목적 밖이라 필터를 끈다(addFilters=false).
 */
@WebMvcTest(IndicatorController.class)
@AutoConfigureMockMvc(addFilters = false)
class IndicatorControllerMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetIndicatorsUseCase getIndicatorsUseCase;
    @MockitoBean
    private GetIndicatorSeriesUseCase getIndicatorSeriesUseCase;

    private final Indicator baseRate = new Indicator("BASE_RATE", "한국은행 기준금리", "%",
            IndicatorCycle.D, "722Y001", "0101000", null);
    private final IndicatorValue latest = new IndicatorValue(1L, "BASE_RATE",
            LocalDate.of(2026, 6, 1), new BigDecimal("3.25"), ValueSource.SEED, null);
    private final IndicatorValue.Change change =
            new IndicatorValue.Change(new BigDecimal("0.25"), new BigDecimal("8.3333"));

    @Test
    @DisplayName("GET /indicators — 실제 Boot Jackson: 날짜=ISO 문자열, 값/변동=JSON number")
    void listWireContract() throws Exception {
        when(getIndicatorsUseCase.getIndicators())
                .thenReturn(List.of(new IndicatorSnapshot(baseRate, latest, change)));

        mockMvc.perform(get("/api/economics/indicators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("BASE_RATE"))
                .andExpect(jsonPath("$[0].cycle").value("D"))
                .andExpect(jsonPath("$[0].latest.observedDate").isString())
                .andExpect(jsonPath("$[0].latest.observedDate").value("2026-06-01"))
                .andExpect(jsonPath("$[0].latest.value").isNumber())
                .andExpect(jsonPath("$[0].latest.value").value(3.25))
                .andExpect(jsonPath("$[0].change.amount").isNumber())
                .andExpect(jsonPath("$[0].change.amount").value(0.25))
                .andExpect(jsonPath("$[0].change.ratePercent").isNumber())
                .andExpect(jsonPath("$[0].change.ratePercent").value(8.3333));
    }

    @Test
    @DisplayName("GET /indicators — 0관측치 스냅샷은 latest/change 가 JSON null")
    void listZeroObservationNulls() throws Exception {
        when(getIndicatorsUseCase.getIndicators())
                .thenReturn(List.of(new IndicatorSnapshot(baseRate, null, null)));

        mockMvc.perform(get("/api/economics/indicators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("BASE_RATE"))
                .andExpect(jsonPath("$[0].latest").isEmpty())
                .andExpect(jsonPath("$[0].change").isEmpty());
    }

    @Test
    @DisplayName("GET /{code}/series — points[].observedDate=ISO 문자열, value=number, source=문자열")
    void seriesWireContract() throws Exception {
        List<IndicatorValue> points = List.of(
                new IndicatorValue(1L, "BASE_RATE", LocalDate.of(2026, 1, 1),
                        new BigDecimal("3.00"), ValueSource.SEED, null),
                new IndicatorValue(2L, "BASE_RATE", LocalDate.of(2026, 6, 1),
                        new BigDecimal("3.25"), ValueSource.ECOS, null));
        when(getIndicatorsUseCase.getIndicator("BASE_RATE"))
                .thenReturn(new IndicatorSnapshot(baseRate, latest, change));
        when(getIndicatorSeriesUseCase.getSeries(any(), any(), any())).thenReturn(points);

        mockMvc.perform(get("/api/economics/indicators/BASE_RATE/series"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("BASE_RATE"))
                .andExpect(jsonPath("$.name").value("한국은행 기준금리"))
                .andExpect(jsonPath("$.unit").value("%"))
                .andExpect(jsonPath("$.points[0].observedDate").isString())
                .andExpect(jsonPath("$.points[0].observedDate").value("2026-01-01"))
                .andExpect(jsonPath("$.points[0].value").isNumber())
                .andExpect(jsonPath("$.points[0].value").value(3.00))
                .andExpect(jsonPath("$.points[0].source").isString())
                .andExpect(jsonPath("$.points[0].source").value("SEED"))
                .andExpect(jsonPath("$.points[1].source").value("ECOS"));
    }

    @Test
    @DisplayName("GET /{code}/latest — 없는 code 는 GlobalExceptionHandler 로 404")
    void unknownCode404() throws Exception {
        when(getIndicatorsUseCase.getIndicator("NOPE"))
                .thenThrow(new IndicatorNotFoundException("NOPE"));

        mockMvc.perform(get("/api/economics/indicators/NOPE/latest"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }
}
