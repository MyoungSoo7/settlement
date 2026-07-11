package github.lms.lemuel.company.adapter.in.web;

import github.lms.lemuel.company.application.port.in.LinkSellerUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SellerLinkAdminControllerTest {

    @Mock
    private LinkSellerUseCase linkSellerUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SellerLinkAdminController(linkSellerUseCase))
                .build();
    }

    @Test
    @DisplayName("POST /{sellerId}/link/{stockCode} — 링크 위임 후 200 메시지")
    void link() throws Exception {
        mockMvc.perform(post("/admin/company/sellers/7/link/005930"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("링크 완료: seller=7 → 005930"));

        verify(linkSellerUseCase).link(7L, "005930");
    }
}
