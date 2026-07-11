package github.lms.lemuel.company.adapter.in.web;

import github.lms.lemuel.company.application.port.in.GetCompanyDocumentsUseCase;
import github.lms.lemuel.company.config.AdminApiKeyFilter;
import github.lms.lemuel.company.config.SecurityConfig;
import github.lms.lemuel.company.domain.CompanyDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CEO 브리핑 문서 엔드포인트 인가 게이트 검증 — SecurityConfig(+JWT 빈 @Import) 를 실제 부팅해
 * 문서 목록·다운로드가 ADMIN/MANAGER 전용임을 확인한다(감사 IDOR 수정 회귀 방어).
 * JWT 시크릿은 테스트 태스크 env(JWT_SECRET)로 공급되어 JwtUtil 이 정상 생성된다.
 */
@WebMvcTest(controllers = CompanyDocumentController.class)
@Import({SecurityConfig.class, AdminApiKeyFilter.class})
class CompanyDocumentSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetCompanyDocumentsUseCase getCompanyDocumentsUseCase;

    private static CompanyDocument document() {
        return CompanyDocument.rehydrate(5L, "005930", "브리핑", "브리핑.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                123, Instant.parse("2026-07-07T09:00:00Z"));
    }

    @Test
    @DisplayName("문서 다운로드 — 미인증이면 401")
    void downloadUnauthenticated401() throws Exception {
        mockMvc.perform(get("/api/company/documents/5/download"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("문서 목록 — 미인증이면 401")
    void listUnauthenticated401() throws Exception {
        mockMvc.perform(get("/api/company/companies/005930/documents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("문서 다운로드 — USER 권한이면 403")
    @WithMockUser(roles = "USER")
    void downloadUserForbidden403() throws Exception {
        mockMvc.perform(get("/api/company/documents/5/download"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("문서 다운로드 — ADMIN 은 인가 통과(200)")
    @WithMockUser(roles = "ADMIN")
    void downloadAdminOk() throws Exception {
        when(getCompanyDocumentsUseCase.download(5L)).thenReturn(
                Optional.of(new GetCompanyDocumentsUseCase.DocumentDownload(document(), new byte[]{1, 2, 3})));

        mockMvc.perform(get("/api/company/documents/5/download"))
                .andExpect(status().isOk());
    }
}
