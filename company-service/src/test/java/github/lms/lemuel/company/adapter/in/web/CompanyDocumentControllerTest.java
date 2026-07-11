package github.lms.lemuel.company.adapter.in.web;

import github.lms.lemuel.company.application.port.in.GetCompanyDocumentsUseCase;
import github.lms.lemuel.company.domain.CompanyDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CompanyDocumentControllerTest {

    @Mock
    private GetCompanyDocumentsUseCase getCompanyDocumentsUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CompanyDocumentController(getCompanyDocumentsUseCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private CompanyDocument document() {
        return CompanyDocument.rehydrate(5L, "005930", "브리핑", "브리핑.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                123, Instant.parse("2026-07-07T09:00:00Z"));
    }

    @Test
    @DisplayName("GET /companies/{stockCode}/documents — 메타 목록")
    void byCompany() throws Exception {
        when(getCompanyDocumentsUseCase.byCompany("005930")).thenReturn(List.of(document()));

        mockMvc.perform(get("/api/company/companies/005930/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(5))
                .andExpect(jsonPath("$[0].fileName").value("브리핑.docx"))
                .andExpect(jsonPath("$[0].sizeBytes").value(123));
    }

    @Test
    @DisplayName("GET /documents/{id}/download — 바이트 + Content-Disposition 헤더")
    void download() throws Exception {
        byte[] bytes = {1, 2, 3};
        when(getCompanyDocumentsUseCase.download(5L))
                .thenReturn(Optional.of(new GetCompanyDocumentsUseCase.DocumentDownload(document(), bytes)));

        mockMvc.perform(get("/api/company/documents/5/download"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("filename")))
                .andExpect(content().bytes(bytes));
    }

    @Test
    @DisplayName("GET /documents/{id}/download — 미존재 시 404")
    void downloadNotFound() throws Exception {
        when(getCompanyDocumentsUseCase.download(9L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/company/documents/9/download"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }
}
