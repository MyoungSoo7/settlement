package github.lms.lemuel.commondata.application.service;

import github.lms.lemuel.commondata.application.port.in.RegisterDataSourceUseCase.RegisterCommand;
import github.lms.lemuel.commondata.application.port.out.LoadDataSourcePort;
import github.lms.lemuel.commondata.application.port.out.SaveDataSourcePort;
import github.lms.lemuel.commondata.domain.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataSourceAdminServiceTest {

    @Mock
    private LoadDataSourcePort loadDataSourcePort;

    @Mock
    private SaveDataSourcePort saveDataSourcePort;

    private DataSourceAdminService service;

    @BeforeEach
    void setUp() {
        service = new DataSourceAdminService(loadDataSourcePort, saveDataSourcePort);
    }

    @Test
    @DisplayName("신규 등록 — 미지정 필드는 기본값(pageSize 100, enabled true)")
    void registersNewSource() {
        when(loadDataSourcePort.findByCode("new-source")).thenReturn(Optional.empty());
        when(saveDataSourcePort.upsert(any())).thenAnswer(inv -> inv.getArgument(0));

        DataSource saved = service.register(new RegisterCommand(
                "new-source", "새 소스", "https://apis.data.go.kr/x",
                Map.of("_type", "json"), List.of("id"), null, null, "설명"));

        assertNull(saved.id());
        assertEquals(DataSource.DEFAULT_PAGE_SIZE, saved.pageSize());
        assertTrue(saved.enabled());
        assertEquals(Map.of("_type", "json"), saved.defaultParams());
    }

    @Test
    @DisplayName("기존 소스 부분 갱신 — null 필드는 기존 값 보존, id 유지")
    void updatesExistingSourcePartially() {
        DataSource existing = new DataSource(7L, "kasi-rest-days", "특일정보",
                "https://apis.data.go.kr/x", Map.of("solYear", "2026"), List.of("locdate", "seq"),
                50, true, "기존 설명", null);
        when(loadDataSourcePort.findByCode("kasi-rest-days")).thenReturn(Optional.of(existing));

        service.register(new RegisterCommand(
                "kasi-rest-days", null, null, null, null, 200, false, null));

        ArgumentCaptor<DataSource> captor = ArgumentCaptor.forClass(DataSource.class);
        verify(saveDataSourcePort).upsert(captor.capture());
        DataSource merged = captor.getValue();
        assertEquals(7L, merged.id());
        assertEquals("특일정보", merged.name());
        assertEquals(Map.of("solYear", "2026"), merged.defaultParams());
        assertEquals(200, merged.pageSize());
        assertEquals(false, merged.enabled());
        assertEquals("기존 설명", merged.description());
    }

    @Test
    @DisplayName("신규인데 name/endpoint 누락이면 도메인 검증이 400 유도")
    void rejectsIncompleteNewSource() {
        when(loadDataSourcePort.findByCode("incomplete")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.register(new RegisterCommand(
                "incomplete", null, null, null, null, null, null, null)));
    }

    @Test
    @DisplayName("code 누락 거부")
    void rejectsNullCode() {
        assertThrows(IllegalArgumentException.class, () -> service.register(new RegisterCommand(
                null, "이름", "https://apis.data.go.kr/x", null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.register(null));
    }
}
