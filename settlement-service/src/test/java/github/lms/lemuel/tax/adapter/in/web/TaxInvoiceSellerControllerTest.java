package github.lms.lemuel.tax.adapter.in.web;

import github.lms.lemuel.common.config.JacksonCompatConfig;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.exception.GlobalExceptionHandler;
import github.lms.lemuel.tax.application.port.in.GetTaxInvoiceUseCase;
import github.lms.lemuel.tax.domain.TaxInvoice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 세금계산서 셀러 API 의 IDOR 응답 계약 검증 — 소유권 불일치는 403 이어야 한다.
 *
 * <p>컨트롤러가 던지는 {@code AccessDeniedException} 이 (보안 필터가 아니라) 전역
 * {@code GlobalExceptionHandler} 의 catch-all({@code Exception.class} → 500)에 먼저 잡히면
 * 문서화된 403 계약이 500 으로 새는 버그다 — 이 테스트가 그 회귀 가드다.
 */
@WebMvcTest(controllers = TaxInvoiceSellerController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({JacksonCompatConfig.class, GlobalExceptionHandler.class})
class TaxInvoiceSellerControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean GetTaxInvoiceUseCase getInvoiceUseCase;

    private static Authentication userAuth(Long userId) {
        AuthPrincipal principal = new AuthPrincipal(userId, "seller@lemuel.dev", "USER");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static TaxInvoice invoiceOwnedBy(Long sellerId) {
        return TaxInvoice.rehydrate(1L, 5L, sellerId, new BigDecimal("100000"),
                new BigDecimal("10000"), new BigDecimal("110000"),
                LocalDate.of(2026, 8, 1), TaxInvoice.numberFor(5L),
                LocalDateTime.of(2026, 8, 1, 12, 0));
    }

    @Test
    @DisplayName("GET — 본인 소유 세금계산서는 200")
    void get_ownInvoice_ok() throws Exception {
        when(getInvoiceUseCase.bySettlementId(5L)).thenReturn(Optional.of(invoiceOwnedBy(10L)));

        mockMvc.perform(get("/api/tax-invoices/settlement/5").principal(userAuth(10L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlementId").value(5));
    }

    @Test
    @DisplayName("GET — 타인 소유 세금계산서는 403 (IDOR 계약, 500 누수 회귀 가드)")
    void get_othersInvoice_forbidden() throws Exception {
        when(getInvoiceUseCase.bySettlementId(5L)).thenReturn(Optional.of(invoiceOwnedBy(99L)));

        mockMvc.perform(get("/api/tax-invoices/settlement/5").principal(userAuth(10L)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /pdf — 타인 소유는 403 (IDOR 계약, 500 누수 회귀 가드)")
    void getPdf_othersInvoice_forbidden() throws Exception {
        when(getInvoiceUseCase.bySettlementId(5L)).thenReturn(Optional.of(invoiceOwnedBy(99L)));

        mockMvc.perform(get("/api/tax-invoices/settlement/5/pdf").principal(userAuth(10L)))
                .andExpect(status().isForbidden());
    }
}
