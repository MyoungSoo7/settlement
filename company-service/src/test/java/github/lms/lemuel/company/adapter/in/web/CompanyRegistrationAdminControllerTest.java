package github.lms.lemuel.company.adapter.in.web;

import github.lms.lemuel.company.application.port.in.RegisterCompaniesUseCase;
import github.lms.lemuel.company.application.port.in.RegisterCompaniesUseCase.RegisterCommand;
import github.lms.lemuel.company.application.port.in.RegisterCompaniesUseCase.RegisterResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CompanyRegistrationAdminControllerTest {

    @Mock
    private RegisterCompaniesUseCase registerCompaniesUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CompanyRegistrationAdminController(registerCompaniesUseCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /admin/company/companies — companies 배열을 명령으로 매핑해 등록, 결과 카운트 반환")
    void register() throws Exception {
        when(registerCompaniesUseCase.register(any())).thenReturn(new RegisterResult(2, 1, 1, 0));

        mockMvc.perform(post("/admin/company/companies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"companies":[
                                  {"stockCode":"005930","corpCode":"00126380","name":"삼성전자","market":"KOSPI"},
                                  {"stockCode":"035420","corpCode":"00266961","name":"NAVER","market":"KOSPI"}
                                ]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(2))
                .andExpect(jsonPath("$.registered").value(1))
                .andExpect(jsonPath("$.updated").value(1));

        ArgumentCaptor<List<RegisterCommand>> captor = ArgumentCaptor.forClass(List.class);
        verify(registerCompaniesUseCase).register(captor.capture());
        List<RegisterCommand> commands = captor.getValue();
        assertEquals(2, commands.size());
        assertEquals("005930", commands.get(0).stockCode());
        assertEquals("삼성전자", commands.get(0).name());
    }

    @Test
    @DisplayName("POST /admin/company/companies — 빈 목록은 use case 의 IllegalArgument → 400")
    void registerEmpty() throws Exception {
        when(registerCompaniesUseCase.register(any()))
                .thenThrow(new IllegalArgumentException("등록할 기업 목록이 비어 있습니다"));

        mockMvc.perform(post("/admin/company/companies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companies\":[]}"))
                .andExpect(status().isBadRequest());
    }
}
