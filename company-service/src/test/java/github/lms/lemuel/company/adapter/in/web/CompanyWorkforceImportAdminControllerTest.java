package github.lms.lemuel.company.adapter.in.web;

import github.lms.lemuel.company.application.port.in.ImportCompanyWorkforceUseCase;
import github.lms.lemuel.company.application.port.in.ImportCompanyWorkforceUseCase.ImportResult;
import github.lms.lemuel.company.audit.application.port.out.RecordAuditPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CompanyWorkforceImportAdminControllerTest {

    @Mock
    private ImportCompanyWorkforceUseCase importCompanyWorkforceUseCase;

    private final RecordAuditPort recordAuditPort = mock(RecordAuditPort.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CompanyWorkforceImportAdminController(importCompanyWorkforceUseCase, recordAuditPort))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /admin/company/workforce/import — 경로를 위임하고 결과를 반환 + 감사기록")
    void importCsv() throws Exception {
        when(importCompanyWorkforceUseCase.importFrom(Path.of("/data/workforce.csv")))
                .thenReturn(new ImportResult(5, 3, 2, 0, java.util.List.of("2026-06")));

        mockMvc.perform(post("/admin/company/workforce/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/data/workforce.csv\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(5))
                .andExpect(jsonPath("$.imported").value(3))
                .andExpect(jsonPath("$.skipped").value(2))
                .andExpect(jsonPath("$.aggregatedMonths[0]").value("2026-06"));

        verify(recordAuditPort).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("감사기록 resourceId 는 절대경로 전체가 아니라 파일명만 — " +
            "audit_logs.resource_id 가 VARCHAR(64) 라 긴 절대경로를 그대로 넘기면 flush 시점에 " +
            "truncation 오류로 REQUIRES_NEW 감사 트랜잭션이 rollback-only 가 되어 " +
            "UnexpectedRollbackException 으로 요청 전체가 500 되는 것을 재현")
    void auditResourceIdIsFileNameNotFullPath() throws Exception {
        String longPath = "C:\\Users\\iamip\\IdeaProjects\\kubenetis\\settlement\\company-service\\src\\main\\"
                + "resources\\국민연금공단_국민연금 가입 사업장 내역_20260723.csv";
        when(importCompanyWorkforceUseCase.importFrom(Path.of(longPath)))
                .thenReturn(new ImportResult(1, 1, 0, 0, java.util.List.of("2026-06")));

        mockMvc.perform(post("/admin/company/workforce/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"" + longPath.replace("\\", "\\\\") + "\"}"))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(recordAuditPort).record(any(), any(), captor.capture(), any());
        String resourceId = captor.getValue();
        assertEquals("국민연금공단_국민연금 가입 사업장 내역_20260723.csv", resourceId);
        assertTrue(resourceId.length() <= 64, "resource_id 는 VARCHAR(64) 를 넘으면 안 된다");
    }
}
