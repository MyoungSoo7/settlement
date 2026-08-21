package github.lms.lemuel.tax.adapter.in.web;

import github.lms.lemuel.common.config.JacksonCompatConfig;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.exception.GlobalExceptionHandler;
import github.lms.lemuel.tax.application.exception.TaxInvoiceScanNotFoundException;
import github.lms.lemuel.tax.application.port.in.GetTaxInvoiceScanUseCase;
import github.lms.lemuel.tax.application.port.in.ReviewTaxInvoiceScanUseCase;
import github.lms.lemuel.tax.domain.scan.ExtractedTaxInvoice;
import github.lms.lemuel.tax.domain.scan.TaxInvoiceScan;
import github.lms.lemuel.tax.domain.scan.TaxInvoiceScanStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 리뷰 큐 API 계약 — 목록·반려·재대사. */
@WebMvcTest(controllers = TaxInvoiceScanAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({JacksonCompatConfig.class, GlobalExceptionHandler.class})
class TaxInvoiceScanAdminControllerTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 11, 3, 0, 0, 0, ZoneOffset.UTC);

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean GetTaxInvoiceScanUseCase getUseCase;
    @MockitoBean ReviewTaxInvoiceScanUseCase reviewUseCase;

    private static TaxInvoiceScan scan(TaxInvoiceScanStatus status) {
        ExtractedTaxInvoice fields = ExtractedTaxInvoice.of("101-81-00001", null,
                LocalDate.of(2026, 8, 1), new BigDecimal("100000"), new BigDecimal("10000"),
                new BigDecimal("110000"), "TI-0000000005", new BigDecimal("0.61"), new BigDecimal("0.61"));
        return TaxInvoiceScan.rehydrate(3L, 7L, "invoice.png", "image/png", "a".repeat(64), 4L,
                fields, "gemini-2.5-flash", status, 42L, "공급가액 불일치", NOW, NOW);
    }

    @Test
    @DisplayName("리뷰 큐 — 상태로 조회한다")
    void queue() throws Exception {
        when(getUseCase.byStatus(eq(TaxInvoiceScanStatus.MISMATCHED), eq(50)))
                .thenReturn(List.of(scan(TaxInvoiceScanStatus.MISMATCHED)));

        mockMvc.perform(get("/admin/tax/scans?status=MISMATCHED&limit=50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(3))
                .andExpect(jsonPath("$[0].status").value("MISMATCHED"))
                .andExpect(jsonPath("$[0].needsReview").value(true))
                .andExpect(jsonPath("$[0].supplierBusinessNo").value("101-81-*****"));
    }

    @Test
    @DisplayName("반려 — REJECTED 를 돌려준다")
    void reject() throws Exception {
        when(reviewUseCase.reject(eq(3L), eq("위조 의심"))).thenReturn(scan(TaxInvoiceScanStatus.REJECTED));

        mockMvc.perform(post("/admin/tax/scans/3/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"위조 의심\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    @DisplayName("재대사 — 갱신된 상태를 돌려준다")
    void rematch() throws Exception {
        when(reviewUseCase.rematch(3L)).thenReturn(scan(TaxInvoiceScanStatus.MATCHED));

        mockMvc.perform(post("/admin/tax/scans/3/rematch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MATCHED"))
                .andExpect(jsonPath("$.linkedTaxInvoiceId").value(42));
    }

    @Test
    @DisplayName("없는 스캔 반려는 404 (500 누수 회귀 가드)")
    void rejectMissing() throws Exception {
        when(reviewUseCase.rematch(404L)).thenThrow(new TaxInvoiceScanNotFoundException(404L));

        mockMvc.perform(post("/admin/tax/scans/404/rematch"))
                .andExpect(status().isNotFound());
    }
}
