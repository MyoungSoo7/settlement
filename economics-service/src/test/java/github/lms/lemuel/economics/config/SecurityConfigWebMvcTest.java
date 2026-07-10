package github.lms.lemuel.economics.config;

import github.lms.lemuel.economics.adapter.in.web.IndicatorController;
import github.lms.lemuel.economics.application.port.in.GetIndicatorSeriesUseCase;
import github.lms.lemuel.economics.application.port.in.GetIndicatorsUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SecurityConfig 필터체인을 실제로 부팅해 공개 GET 허용 / 미허용 경로 거부 / CORS 헤더 부여를 검증한다.
 * (필터 On — IndicatorControllerMvcTest 의 addFilters=false 슬라이스와 상보적)
 */
@WebMvcTest(IndicatorController.class)
@Import({SecurityConfig.class, AdminApiKeyFilter.class})
class SecurityConfigWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetIndicatorsUseCase getIndicatorsUseCase;
    @MockitoBean
    private GetIndicatorSeriesUseCase getIndicatorSeriesUseCase;

    @Test
    @DisplayName("공개 GET /api/economics/** 는 무인증 허용 + CORS 허용 오리진 헤더")
    void publicGetAllowedWithCors() throws Exception {
        when(getIndicatorsUseCase.getIndicators()).thenReturn(List.of());

        mockMvc.perform(get("/api/economics/indicators").header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    @DisplayName("화이트리스트 밖 경로는 denyAll → 401")
    void unlistedPathDenied() throws Exception {
        mockMvc.perform(get("/secret/area"))
                .andExpect(status().isUnauthorized());
    }
}
