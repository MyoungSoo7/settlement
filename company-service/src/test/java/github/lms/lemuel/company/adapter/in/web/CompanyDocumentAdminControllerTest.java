package github.lms.lemuel.company.adapter.in.web;

import github.lms.lemuel.company.application.port.in.UploadCompanyDocumentUseCase;
import github.lms.lemuel.company.application.port.in.UploadCompanyDocumentUseCase.UploadCommand;
import github.lms.lemuel.company.audit.application.port.out.RecordAuditPort;
import github.lms.lemuel.company.domain.CompanyDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CompanyDocumentAdminControllerTest {

    @Mock
    private UploadCompanyDocumentUseCase uploadCompanyDocumentUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CompanyDocumentAdminController(uploadCompanyDocumentUseCase, mock(RecordAuditPort.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /admin/company/documents — 멀티파트 업로드 시 201 + 저장 문서 반환, command 에 파일 바이트 전달")
    void upload() throws Exception {
        byte[] bytes = {10, 20, 30};
        MockMultipartFile file = new MockMultipartFile("file", "briefing.docx",
                "application/octet-stream", bytes);
        CompanyDocument saved = CompanyDocument.rehydrate(1L, "005930", "브리핑", "briefing.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                bytes.length, Instant.parse("2026-07-07T09:00:00Z"));
        when(uploadCompanyDocumentUseCase.upload(org.mockito.ArgumentMatchers.any())).thenReturn(saved);

        mockMvc.perform(multipart("/admin/company/documents")
                        .file(file)
                        .param("stockCode", "005930")
                        .param("title", "브리핑"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.fileName").value("briefing.docx"));

        ArgumentCaptor<UploadCommand> captor = ArgumentCaptor.forClass(UploadCommand.class);
        verify(uploadCompanyDocumentUseCase).upload(captor.capture());
        UploadCommand command = captor.getValue();
        assertEquals("005930", command.stockCode());
        assertEquals("브리핑", command.title());
        assertEquals("briefing.docx", command.fileName());
        assertArrayEquals(bytes, command.content());
    }
}
