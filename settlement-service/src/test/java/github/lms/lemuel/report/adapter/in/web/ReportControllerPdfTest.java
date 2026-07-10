package github.lms.lemuel.report.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.report.application.port.in.GenerateCashflowReportUseCase;
import github.lms.lemuel.report.application.port.out.RenderCashflowReportPdfPort;
import github.lms.lemuel.report.domain.BucketGranularity;
import github.lms.lemuel.report.domain.CashflowReconciliation;
import github.lms.lemuel.report.domain.CashflowReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ReportController#getCashflowPdf(LocalDate, LocalDate, String)} 전용 테스트.
 * {@code ReportControllerTest} 는 JSON 엔드포인트만 다루므로 별도 클래스로 분리.
 */
@WebMvcTest(controllers = ReportController.class)
@AutoConfigureMockMvc(addFilters = false)
class ReportControllerPdfTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean GenerateCashflowReportUseCase generateCashflowReportUseCase;
    @MockitoBean RenderCashflowReportPdfPort renderCashflowReportPdfPort;

    @Test
    @DisplayName("GET /api/reports/cashflow/pdf — PDF 다운로드")
    void getCashflowPdf() throws Exception {
        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to = LocalDate.of(2026, 4, 2);
        CashflowReport stub = CashflowReport.of(from, to, BucketGranularity.DAY, List.of(),
                CashflowReconciliation.empty());
        byte[] pdf = new byte[]{0x25, 0x50, 0x44, 0x46};
        when(generateCashflowReportUseCase.generate(any())).thenReturn(stub);
        when(renderCashflowReportPdfPort.render(stub)).thenReturn(pdf);

        mockMvc.perform(get("/api/reports/cashflow/pdf")
                        .param("from", "2026-04-01")
                        .param("to", "2026-04-02")
                        .param("groupBy", "day")
                        .accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/pdf")))
                .andExpect(header().string("Content-Disposition",
                        containsString("cashflow-2026-04-01_to_2026-04-02_day.pdf")));
    }
}
