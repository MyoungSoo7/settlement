package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.in.RegisterCompaniesUseCase.RegisterCommand;
import github.lms.lemuel.company.application.port.in.RegisterCompaniesUseCase.RegisterResult;
import github.lms.lemuel.company.application.port.out.SaveCompanyPort;
import github.lms.lemuel.company.application.port.out.SaveCompanyPort.UpsertResult;
import github.lms.lemuel.company.domain.Company;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyRegistrationServiceTest {

    @Mock
    private SaveCompanyPort saveCompanyPort;

    private CompanyRegistrationService service() {
        return new CompanyRegistrationService(saveCompanyPort);
    }

    @Test
    @DisplayName("register — 명령을 도메인으로 검증해 upsert, 포트 결과를 받아 received 와 함께 반환")
    void register() {
        when(saveCompanyPort.upsertAll(anyList())).thenReturn(new UpsertResult(2, 1, 0));
        List<RegisterCommand> commands = List.of(
                new RegisterCommand("005930", "00126380", "삼성전자", "KOSPI"),
                new RegisterCommand("035420", "00266961", "NAVER", "KOSPI"),
                new RegisterCommand("035720", "", "카카오", "KOSDAQ"));

        RegisterResult result = service().register(commands);

        assertEquals(3, result.received());
        assertEquals(2, result.registered());
        assertEquals(1, result.updated());
        assertEquals(0, result.skipped());

        ArgumentCaptor<List<Company>> captor = ArgumentCaptor.forClass(List.class);
        verify(saveCompanyPort).upsertAll(captor.capture());
        List<Company> saved = captor.getValue();
        assertEquals(3, saved.size());
        assertEquals("삼성전자", saved.get(0).name());
        assertNull(saved.get(2).corpCode(), "공란 corpCode 는 null 로 정규화");
    }

    @Test
    @DisplayName("register — 같은 stockCode 중복은 마지막 값이 이긴다(멱등 upsert)")
    void registerDedup() {
        when(saveCompanyPort.upsertAll(anyList())).thenReturn(new UpsertResult(1, 0, 0));
        List<RegisterCommand> commands = List.of(
                new RegisterCommand("005930", null, "구삼성", "KOSPI"),
                new RegisterCommand("005930", "00126380", "삼성전자", "KOSPI"));

        RegisterResult result = service().register(commands);

        assertEquals(2, result.received());
        ArgumentCaptor<List<Company>> captor = ArgumentCaptor.forClass(List.class);
        verify(saveCompanyPort).upsertAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("삼성전자", captor.getValue().get(0).name());
    }

    @Test
    @DisplayName("register — 빈/누락 목록은 400(IllegalArgument), 포트 미호출")
    void registerEmpty() {
        assertThrows(IllegalArgumentException.class, () -> service().register(List.of()));
        assertThrows(IllegalArgumentException.class, () -> service().register(null));
        verifyNoInteractions(saveCompanyPort);
    }

    @Test
    @DisplayName("register — 잘못된 종목코드는 도메인 검증에서 IllegalArgument")
    void registerInvalidStockCode() {
        List<RegisterCommand> commands = List.of(new RegisterCommand("ABC", null, "이상", "KOSPI"));
        assertThrows(IllegalArgumentException.class, () -> service().register(commands));
        verifyNoInteractions(saveCompanyPort);
    }
}
