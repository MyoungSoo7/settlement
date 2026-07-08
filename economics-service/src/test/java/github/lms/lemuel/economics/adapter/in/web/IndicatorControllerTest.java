package github.lms.lemuel.economics.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import github.lms.lemuel.economics.application.port.in.GetIndicatorSeriesUseCase;
import github.lms.lemuel.economics.application.port.in.GetIndicatorsUseCase;
import github.lms.lemuel.economics.application.port.in.GetIndicatorsUseCase.IndicatorSnapshot;
import github.lms.lemuel.economics.domain.Indicator;
import github.lms.lemuel.economics.domain.IndicatorCycle;
import github.lms.lemuel.economics.domain.IndicatorNotFoundException;
import github.lms.lemuel.economics.domain.IndicatorValue;
import github.lms.lemuel.economics.domain.ValueSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class IndicatorControllerTest {

    @Mock
    private GetIndicatorsUseCase getIndicatorsUseCase;
    @Mock
    private GetIndicatorSeriesUseCase getIndicatorSeriesUseCase;

    private MockMvc mockMvc;

    private final Indicator baseRate = new Indicator("BASE_RATE", "한국은행 기준금리", "%",
            IndicatorCycle.D, "722Y001", "0101000", null);
    private final IndicatorValue latest = new IndicatorValue(1L, "BASE_RATE",
            LocalDate.of(2026, 6, 1), new BigDecimal("3.25"), ValueSource.SEED, null);
    private final IndicatorValue.Change change =
            new IndicatorValue.Change(new BigDecimal("0.25"), new BigDecimal("8.3333"));

    @BeforeEach
    void setUp() {
        // 프로덕션 Boot ObjectMapper 와 동일하게 LocalDate → ISO 문자열 직렬화(타임스탬프 배열 방지).
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new IndicatorController(getIndicatorsUseCase, getIndicatorSeriesUseCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("GET /api/economics/indicators — 카탈로그+최신값+변동 목록 200")
    void list() throws Exception {
        when(getIndicatorsUseCase.getIndicators())
                .thenReturn(List.of(new IndicatorSnapshot(baseRate, latest, change)));

        mockMvc.perform(get("/api/economics/indicators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("BASE_RATE"))
                .andExpect(jsonPath("$[0].name").value("한국은행 기준금리"))
                .andExpect(jsonPath("$[0].unit").value("%"))
                .andExpect(jsonPath("$[0].cycle").value("D"))
                .andExpect(jsonPath("$[0].latest.observedDate").value("2026-06-01"))
                .andExpect(jsonPath("$[0].latest.value").value(3.25))
                .andExpect(jsonPath("$[0].change.amount").value(0.25))
                .andExpect(jsonPath("$[0].change.ratePercent").value(8.3333));
    }

    @Test
    @DisplayName("GET /api/economics/indicators/{code}/latest — 단건 200")
    void single() throws Exception {
        when(getIndicatorsUseCase.getIndicator("BASE_RATE"))
                .thenReturn(new IndicatorSnapshot(baseRate, latest, change));

        mockMvc.perform(get("/api/economics/indicators/BASE_RATE/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("BASE_RATE"))
                .andExpect(jsonPath("$.latest.value").value(3.25))
                .andExpect(jsonPath("$.change.amount").value(0.25));
    }

    @Test
    @DisplayName("GET /latest — 없는 code 는 404 + message")
    void unknownCode() throws Exception {
        when(getIndicatorsUseCase.getIndicator("NOPE"))
                .thenThrow(new IndicatorNotFoundException("NOPE"));

        mockMvc.perform(get("/api/economics/indicators/NOPE/latest"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("GET /{code}/series — 기간 미지정(기본 1년) 200")
    void series() throws Exception {
        List<IndicatorValue> points = List.of(
                new IndicatorValue(1L, "BASE_RATE", LocalDate.of(2026, 1, 1),
                        new BigDecimal("3.00"), ValueSource.SEED, null),
                new IndicatorValue(2L, "BASE_RATE", LocalDate.of(2026, 6, 1),
                        new BigDecimal("3.25"), ValueSource.SEED, null));
        when(getIndicatorSeriesUseCase.getSeries(eq("BASE_RATE"), isNull(), isNull()))
                .thenReturn(points);
        when(getIndicatorsUseCase.getIndicator("BASE_RATE"))
                .thenReturn(new IndicatorSnapshot(baseRate, latest, change));

        mockMvc.perform(get("/api/economics/indicators/BASE_RATE/series"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("BASE_RATE"))
                .andExpect(jsonPath("$.name").value("한국은행 기준금리"))
                .andExpect(jsonPath("$.unit").value("%"))
                .andExpect(jsonPath("$.points[0].observedDate").value("2026-01-01"))
                .andExpect(jsonPath("$.points[0].value").value(3.00))
                .andExpect(jsonPath("$.points[0].source").value("SEED"))
                .andExpect(jsonPath("$.points[1].observedDate").value("2026-06-01"));
    }

    @Test
    @DisplayName("GET /{code}/series — from > to (유효 code) 는 400")
    void seriesInvertedRange() throws Exception {
        when(getIndicatorsUseCase.getIndicator("BASE_RATE"))
                .thenReturn(new IndicatorSnapshot(baseRate, latest, change));
        when(getIndicatorSeriesUseCase.getSeries(eq("BASE_RATE"),
                eq(LocalDate.of(2026, 6, 30)), eq(LocalDate.of(2026, 1, 1))))
                .thenThrow(new IllegalArgumentException("조회 기간이 올바르지 않습니다"));

        mockMvc.perform(get("/api/economics/indicators/BASE_RATE/series")
                        .param("from", "2026-06-30")
                        .param("to", "2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("GET /{code}/series — 없는 code 는 404")
    void seriesUnknownCode() throws Exception {
        when(getIndicatorsUseCase.getIndicator("NOPE"))
                .thenThrow(new IndicatorNotFoundException("NOPE"));

        mockMvc.perform(get("/api/economics/indicators/NOPE/series"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("GET /{code}/series — 없는 code + from>to 동시 입력은 404(400 아님) — 존재검증 우선")
    void seriesUnknownCodeWithInvertedRangeIs404() throws Exception {
        // 존재검증이 기간검증보다 먼저라, getIndicator 가 404 를 내고 getSeries 는 호출되지 않는다.
        when(getIndicatorsUseCase.getIndicator("NOPE"))
                .thenThrow(new IndicatorNotFoundException("NOPE"));

        mockMvc.perform(get("/api/economics/indicators/NOPE/series")
                        .param("from", "2026-06-30")
                        .param("to", "2026-01-01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("GET /indicators — 관측치 0건 스냅샷은 latest·change 가 JSON null 로 직렬화")
    void listWithZeroObservationSerializesNulls() throws Exception {
        when(getIndicatorsUseCase.getIndicators())
                .thenReturn(List.of(new IndicatorSnapshot(baseRate, null, null)));

        mockMvc.perform(get("/api/economics/indicators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("BASE_RATE"))
                .andExpect(jsonPath("$[0].latest").isEmpty())   // null → 화면에서 N/A
                .andExpect(jsonPath("$[0].change").isEmpty());
    }
}
