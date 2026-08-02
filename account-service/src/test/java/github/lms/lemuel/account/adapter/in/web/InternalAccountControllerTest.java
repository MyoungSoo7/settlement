package github.lms.lemuel.account.adapter.in.web;

import github.lms.lemuel.account.application.port.in.AccountQueryUseCase;
import github.lms.lemuel.account.application.port.in.SellerFundingQuery.SellerFunding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 내부 재원 API 응답 매핑 고정 — card-service 의 한도 산정 입력이므로 필드명·타입이 계약이다.
 * 금액은 JSON 문자열(DATA-STANDARD N5)로 나가야 한다 — JS Number 로 정밀도가 깎이면 한도가 틀어진다.
 */
@ExtendWith(MockitoExtension.class)
class InternalAccountControllerTest {

    @Mock AccountQueryUseCase accountQueryUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new InternalAccountController(accountQueryUseCase))
                .build();
    }

    @Test
    @DisplayName("셀러 재원은 SELLER_PAYABLE·HOLDBACK_PAYABLE 두 잔액을 문자열로 반환한다")
    void sellerFundingReturnsTwoBalancesAsStrings() throws Exception {
        when(accountQueryUseCase.sellerFunding("777")).thenReturn(
                new SellerFunding("777", new BigDecimal("170000.00"), new BigDecimal("10000.00")));

        mockMvc.perform(get("/internal/account/sellers/777/funding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerId").value("777"))
                .andExpect(jsonPath("$.sellerPayable").value("170000.00"))
                .andExpect(jsonPath("$.holdbackPayable").value("10000.00"));
    }

    @Test
    @DisplayName("잔액 행이 없는 셀러는 0 — null 을 노출하지 않는다")
    void unknownSellerReturnsZeros() throws Exception {
        when(accountQueryUseCase.sellerFunding("999")).thenReturn(
                new SellerFunding("999", BigDecimal.ZERO, BigDecimal.ZERO));

        mockMvc.perform(get("/internal/account/sellers/999/funding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerPayable").value("0"))
                .andExpect(jsonPath("$.holdbackPayable").value("0"));
    }
}
