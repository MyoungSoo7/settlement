package github.lms.lemuel.settlement.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.settlement.application.port.in.GenerateSettlementPdfUseCase;
import github.lms.lemuel.settlement.application.port.in.GetSettlementUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SettlementController#downloadSettlementPdf(Long)} 전용 테스트.
 *
 * <p>{@code SettlementControllerTest} 는 Boot4 WebMvcTest 의 binary response produces
 * 처리 방식 변경으로 이 엔드포인트를 {@code @Disabled} 처리했으나, {@code Accept} 헤더를
 * 명시적으로 {@code application/pdf} 로 지정하면 컨텐츠 협상이 정상 동작한다.
 */
@WebMvcTest(controllers = SettlementController.class)
@AutoConfigureMockMvc(addFilters = false)
class SettlementControllerPdfTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean GetSettlementUseCase getSettlementUseCase;
    @MockitoBean GenerateSettlementPdfUseCase generateSettlementPdfUseCase;

    @Test
    @DisplayName("GET /settlements/{id}/pdf — PDF 다운로드")
    void downloadPdf() throws Exception {
        byte[] pdf = new byte[]{0x25, 0x50, 0x44, 0x46};
        when(generateSettlementPdfUseCase.generate(1L)).thenReturn(pdf);

        mockMvc.perform(get("/settlements/1/pdf").accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/pdf")))
                .andExpect(header().string("Content-Disposition", containsString("settlement-1.pdf")));
    }
}
