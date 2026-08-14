package github.lms.lemuel.tax.adapter.in.web;

import github.lms.lemuel.common.config.JacksonCompatConfig;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.exception.GlobalExceptionHandler;
import github.lms.lemuel.tax.application.exception.TaxOcrUnavailableException;
import github.lms.lemuel.tax.application.port.in.ExtractTaxInvoiceScanUseCase;
import github.lms.lemuel.tax.application.port.in.ExtractTaxInvoiceScanUseCase.UploadTaxInvoiceScanCommand;
import github.lms.lemuel.tax.application.port.in.GetTaxInvoiceScanUseCase;
import github.lms.lemuel.tax.domain.scan.ExtractedTaxInvoice;
import github.lms.lemuel.tax.domain.scan.TaxInvoiceScan;
import github.lms.lemuel.tax.domain.scan.TaxInvoiceScanStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스캔 업로드·조회 API 계약 — <b>셀러 식별자는 JWT 주체에서만</b> 파생하고, 남의 스캔은 403,
 * OCR 불가는 503, 응답의 사업자번호는 마스킹된다.
 */
@WebMvcTest(controllers = TaxInvoiceScanController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({JacksonCompatConfig.class, GlobalExceptionHandler.class})
class TaxInvoiceScanControllerTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 11, 3, 0, 0, 0, ZoneOffset.UTC);

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean ExtractTaxInvoiceScanUseCase extractUseCase;
    @MockitoBean GetTaxInvoiceScanUseCase getUseCase;

    private static Authentication userAuth(Long userId) {
        AuthPrincipal principal = new AuthPrincipal(userId, "seller@lemuel.dev", "USER");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "invoice.png", "image/png",
                "scan".getBytes(StandardCharsets.UTF_8));
    }

    private static TaxInvoiceScan scanOwnedBy(Long sellerId, TaxInvoiceScanStatus status) {
        ExtractedTaxInvoice fields = ExtractedTaxInvoice.of("101-81-00001", "220-81-00001",
                LocalDate.of(2026, 8, 1), new BigDecimal("100000"), new BigDecimal("10000"),
                new BigDecimal("110000"), "TI-0000000005", new BigDecimal("0.93"));
        return TaxInvoiceScan.rehydrate(3L, sellerId, "invoice.png", "image/png", "a".repeat(64), 4L,
                fields, "gemini-2.5-flash", status, 42L, null, NOW, NOW);
    }

    @Test
    @DisplayName("업로드 — 201 과 대사 결과를 돌려준다")
    void upload() throws Exception {
        when(extractUseCase.extract(any(UploadTaxInvoiceScanCommand.class)))
                .thenReturn(scanOwnedBy(10L, TaxInvoiceScanStatus.MATCHED));

        mockMvc.perform(multipart("/api/tax-invoices/scans").file(file()).principal(userAuth(10L)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("MATCHED"))
                .andExpect(jsonPath("$.linkedTaxInvoiceId").value(42))
                .andExpect(jsonPath("$.supplyAmount").value(100000));
    }

    @Test
    @DisplayName("★ 응답의 사업자등록번호는 마스킹된다 (PII)")
    void masksBusinessNumbers() throws Exception {
        when(extractUseCase.extract(any(UploadTaxInvoiceScanCommand.class)))
                .thenReturn(scanOwnedBy(10L, TaxInvoiceScanStatus.MATCHED));

        mockMvc.perform(multipart("/api/tax-invoices/scans").file(file()).principal(userAuth(10L)))
                .andExpect(jsonPath("$.supplierBusinessNo").value("101-81-*****"))
                .andExpect(jsonPath("$.buyerBusinessNo").value("220-81-*****"));
    }

    @Test
    @DisplayName("OCR 불가는 503 — 전역 catch-all 500 으로 새지 않는다")
    void ocrUnavailable() throws Exception {
        when(extractUseCase.extract(any(UploadTaxInvoiceScanCommand.class)))
                .thenThrow(new TaxOcrUnavailableException("OCR 미구성"));

        mockMvc.perform(multipart("/api/tax-invoices/scans").file(file()).principal(userAuth(10L)))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("인증 주체가 없으면 403 — 요청이 셀러를 자칭할 수 없다")
    void anonymousUploadForbidden() throws Exception {
        mockMvc.perform(multipart("/api/tax-invoices/scans").file(file()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("조회 — 본인 스캔은 200")
    void getOwnScan() throws Exception {
        when(getUseCase.byId(3L)).thenReturn(Optional.of(scanOwnedBy(10L, TaxInvoiceScanStatus.MISMATCHED)));

        mockMvc.perform(get("/api/tax-invoices/scans/3").principal(userAuth(10L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.status").value("MISMATCHED"));
    }

    @Test
    @DisplayName("★ 남의 스캔 조회는 403 (IDOR 계약, 500 누수 회귀 가드)")
    void getOthersScanForbidden() throws Exception {
        when(getUseCase.byId(3L)).thenReturn(Optional.of(scanOwnedBy(99L, TaxInvoiceScanStatus.MATCHED)));

        mockMvc.perform(get("/api/tax-invoices/scans/3").principal(userAuth(10L)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("없는 스캔은 404")
    void getMissingScan() throws Exception {
        when(getUseCase.byId(404L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/tax-invoices/scans/404").principal(userAuth(10L)))
                .andExpect(status().isNotFound());
    }
}
