package github.lms.lemuel.settlement.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.settlement.application.port.in.GenerateSettlementPdfUseCase;
import github.lms.lemuel.settlement.application.port.in.GetSettlementUseCase;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.exception.SettlementNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = SettlementController.class)
@AutoConfigureMockMvc(addFilters = false)
class SettlementControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean GetSettlementUseCase getSettlementUseCase;
    @MockitoBean GenerateSettlementPdfUseCase generateSettlementPdfUseCase;

    @Test @DisplayName("GET /settlements/{id} - 성공") void getSettlement() throws Exception {
        Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("50000"), LocalDate.now());
        when(getSettlementUseCase.getSettlementById(1L)).thenReturn(s);

        mockMvc.perform(get("/settlements/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentAmount").value(50000));
    }

    @Test @DisplayName("GET /settlements/{id} - 404") void getSettlement_notFound() throws Exception {
        when(getSettlementUseCase.getSettlementById(999L)).thenThrow(new SettlementNotFoundException(999L));

        mockMvc.perform(get("/settlements/999"))
                .andExpect(status().isNotFound());
    }

    @Test @DisplayName("GET /settlements/payment/{paymentId} - 존재") void getByPaymentId() throws Exception {
        Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("30000"), LocalDate.now());
        when(getSettlementUseCase.getSettlementsByPaymentId(1L)).thenReturn(List.of(s));

        mockMvc.perform(get("/settlements/payment/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentAmount").value(30000));
    }

    @Test @DisplayName("GET /settlements/payment/{paymentId} - 미존재") void getByPaymentId_notFound() throws Exception {
        when(getSettlementUseCase.getSettlementsByPaymentId(999L)).thenReturn(List.of());

        mockMvc.perform(get("/settlements/payment/999"))
                .andExpect(status().isNotFound());
    }

    @Test @DisplayName("GET /settlements/{id}/pdf")
    @org.junit.jupiter.api.Disabled("Boot 4 WebMvcTest에서 binary response produces 처리 방식 변경 — 별도 통합테스트로 전환 필요")
    void downloadPdf() throws Exception {
        byte[] pdf = new byte[]{0x25, 0x50, 0x44, 0x46};
        when(generateSettlementPdfUseCase.generate(1L)).thenReturn(pdf);

        mockMvc.perform(get("/settlements/1/pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/pdf")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("settlement-1.pdf")));
    }
}
