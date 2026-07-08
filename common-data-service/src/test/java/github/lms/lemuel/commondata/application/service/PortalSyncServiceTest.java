package github.lms.lemuel.commondata.application.service;

import github.lms.lemuel.commondata.application.port.in.SyncResult;
import github.lms.lemuel.commondata.application.port.out.DataPortalClientPort;
import github.lms.lemuel.commondata.application.port.out.DataPortalClientPort.PortalItem;
import github.lms.lemuel.commondata.application.port.out.LoadDataSourcePort;
import github.lms.lemuel.commondata.application.port.out.SaveDataRecordPort;
import github.lms.lemuel.commondata.domain.DataRecord;
import github.lms.lemuel.commondata.domain.DataSource;
import github.lms.lemuel.commondata.domain.DataSourceNotFoundException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortalSyncServiceTest {

    @Mock
    private DataPortalClientPort portalClient;

    @Mock
    private LoadDataSourcePort loadDataSourcePort;

    @Mock
    private SaveDataRecordPort saveDataRecordPort;

    private PortalSyncService service;

    private final DataSource source = new DataSource(1L, "kasi-rest-days", "특일정보",
            "https://apis.data.go.kr/x", Map.of("_type", "json"), List.of("locdate", "seq"),
            50, true, null, null);

    @BeforeEach
    void setUp() {
        service = new PortalSyncService(portalClient, loadDataSourcePort, saveDataRecordPort);
    }

    @Test
    @DisplayName("인증키 미설정이면 수집 거부")
    void rejectsWhenNotConfigured() {
        when(portalClient.isConfigured()).thenReturn(false);

        assertThrows(IllegalStateException.class,
                () -> service.sync("kasi-rest-days", Map.of()));
        verify(portalClient, never()).fetchItems(any(), anyMap());
    }

    @Test
    @DisplayName("미등록 소스는 404 예외")
    void rejectsUnknownSource() {
        when(portalClient.isConfigured()).thenReturn(true);
        when(loadDataSourcePort.findByCode("nope")).thenReturn(Optional.empty());

        assertThrows(DataSourceNotFoundException.class, () -> service.sync("nope", Map.of()));
    }

    @Test
    @DisplayName("비활성 소스는 수집 거부")
    void rejectsDisabledSource() {
        DataSource disabled = new DataSource(1L, "off-source", "비활성",
                "https://apis.data.go.kr/x", null, null, 100, false, null, null);
        when(portalClient.isConfigured()).thenReturn(true);
        when(loadDataSourcePort.findByCode("off-source")).thenReturn(Optional.of(disabled));

        assertThrows(IllegalStateException.class, () -> service.sync("off-source", Map.of()));
    }

    @Test
    @DisplayName("정상 수집 — 결측 키는 스킵, 저장 실패는 집계만 하고 계속")
    void countsUpsertSkipFail() {
        when(portalClient.isConfigured()).thenReturn(true);
        when(loadDataSourcePort.findByCode("kasi-rest-days")).thenReturn(Optional.of(source));
        when(portalClient.fetchItems(any(), anyMap())).thenReturn(List.of(
                new PortalItem("20260101|1", "{\"a\":1}"),
                new PortalItem(" ", "{\"b\":2}"),          // 키 결측 — 스킵
                new PortalItem("20260216|1", "{\"c\":3}"),
                new PortalItem("20260217|1", "{\"d\":4}")));
        doAnswer(inv -> {
            DataRecord record = inv.getArgument(0);
            if ("20260216|1".equals(record.recordKey())) {
                throw new RuntimeException("boom");
            }
            return null;
        }).when(saveDataRecordPort).upsert(any());

        SyncResult result = service.sync("kasi-rest-days", Map.of("solYear", "2026"));

        assertEquals(new SyncResult(4, 2, 1, 1), result);
        verify(saveDataRecordPort, times(3)).upsert(any());
    }

    @Test
    @DisplayName("override 파라미터는 클라이언트에 그대로 전달, null 은 빈 맵으로")
    void passesOverrideParams() {
        when(portalClient.isConfigured()).thenReturn(true);
        when(loadDataSourcePort.findByCode("kasi-rest-days")).thenReturn(Optional.of(source));
        when(portalClient.fetchItems(any(), anyMap())).thenReturn(List.of());

        service.sync("kasi-rest-days", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(portalClient).fetchItems(any(), captor.capture());
        assertEquals(Map.of(), captor.getValue());
    }
}
