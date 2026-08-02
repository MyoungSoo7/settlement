package github.lms.lemuel.commondata.application.service;

import github.lms.lemuel.commondata.application.port.in.SyncResult;
import github.lms.lemuel.commondata.application.port.out.DataPortalClientPort;
import github.lms.lemuel.commondata.application.port.out.DataPortalClientPort.PortalItem;
import github.lms.lemuel.commondata.application.port.out.LoadDataSourcePort;
import github.lms.lemuel.commondata.application.port.out.SaveDataRecordPort;
import github.lms.lemuel.commondata.domain.DataProvider;
import github.lms.lemuel.commondata.domain.DataRecord;
import github.lms.lemuel.commondata.domain.DataSource;
import github.lms.lemuel.commondata.domain.DataSourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentCaptor.forClass;

class PortalSyncServiceTest {

    private final DataPortalClientPort dataGoKrClient = mock(DataPortalClientPort.class);
    private final DataPortalClientPort seoulClient = mock(DataPortalClientPort.class);
    private final LoadDataSourcePort loadDataSourcePort = mock(LoadDataSourcePort.class);
    private final SaveDataRecordPort saveDataRecordPort = mock(SaveDataRecordPort.class);

    private PortalSyncService service;

    private final DataSource source = new DataSource(1L, "kasi-rest-days", "특일정보",
            "https://apis.data.go.kr/x", null, Map.of("_type", "json"), List.of("locdate", "seq"),
            50, true, null, null);

    private final DataSource seoulSource = new DataSource(2L, "seoul-living-pop", "서울 생활인구",
            "http://openapi.seoul.go.kr:8088", DataProvider.SEOUL_OPENAPI,
            Map.of("service", "SPOP_LOCL_RESD_DONG"), List.of(), 100, true, null, null);

    @BeforeEach
    void setUp() {
        lenient().when(dataGoKrClient.provider()).thenReturn(DataProvider.DATA_GO_KR);
        lenient().when(seoulClient.provider()).thenReturn(DataProvider.SEOUL_OPENAPI);
        lenient().when(dataGoKrClient.isConfigured()).thenReturn(true);
        lenient().when(seoulClient.isConfigured()).thenReturn(true);
        service = new PortalSyncService(List.of(dataGoKrClient, seoulClient),
                loadDataSourcePort, saveDataRecordPort);
    }

    @Test
    @DisplayName("소스 provider 의 인증키 미설정이면 수집 거부 — provider 명시 메시지")
    void rejectsWhenProviderNotConfigured() {
        when(loadDataSourcePort.findByCode("seoul-living-pop")).thenReturn(Optional.of(seoulSource));
        when(seoulClient.isConfigured()).thenReturn(false);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.sync("seoul-living-pop", Map.of()));
        assertTrue(e.getMessage().contains("SEOUL_OPENAPI"));
        verify(seoulClient, never()).fetchItems(any(), anyMap());
        verify(dataGoKrClient, never()).fetchItems(any(), anyMap());
    }

    @Test
    @DisplayName("미등록 소스는 404 예외")
    void rejectsUnknownSource() {
        when(loadDataSourcePort.findByCode("nope")).thenReturn(Optional.empty());

        assertThrows(DataSourceNotFoundException.class, () -> service.sync("nope", Map.of()));
    }

    @Test
    @DisplayName("비활성 소스는 수집 거부")
    void rejectsDisabledSource() {
        DataSource disabled = new DataSource(1L, "off-source", "비활성",
                "https://apis.data.go.kr/x", null, null, null, 100, false, null, null);
        when(loadDataSourcePort.findByCode("off-source")).thenReturn(Optional.of(disabled));

        assertThrows(IllegalStateException.class, () -> service.sync("off-source", Map.of()));
    }

    @Test
    @DisplayName("SEOUL_OPENAPI 소스는 서울 클라이언트로 디스패치 — data.go.kr 클라이언트 미호출")
    void dispatchesByProvider() {
        when(loadDataSourcePort.findByCode("seoul-living-pop")).thenReturn(Optional.of(seoulSource));
        when(seoulClient.fetchItems(any(), anyMap())).thenReturn(List.of(
                new PortalItem("20260801|11000", "{\"a\":1}")));

        SyncResult result = service.sync("seoul-living-pop", Map.of());

        assertEquals(new SyncResult(1, 1, 0, 0), result);
        verify(seoulClient).fetchItems(any(), anyMap());
        verify(dataGoKrClient, never()).fetchItems(any(), anyMap());
    }

    @Test
    @DisplayName("소스 provider 를 담당하는 클라이언트가 없으면 예외")
    void rejectsWhenNoClientForProvider() {
        PortalSyncService onlyDataGoKr = new PortalSyncService(List.of(dataGoKrClient),
                loadDataSourcePort, saveDataRecordPort);
        when(loadDataSourcePort.findByCode("seoul-living-pop")).thenReturn(Optional.of(seoulSource));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> onlyDataGoKr.sync("seoul-living-pop", Map.of()));
        assertTrue(e.getMessage().contains("SEOUL_OPENAPI"));
    }

    @Test
    @DisplayName("정상 수집 — 결측 키는 스킵, 저장 실패는 집계만 하고 계속")
    void countsUpsertSkipFail() {
        when(loadDataSourcePort.findByCode("kasi-rest-days")).thenReturn(Optional.of(source));
        when(dataGoKrClient.fetchItems(any(), anyMap())).thenReturn(List.of(
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
        when(loadDataSourcePort.findByCode("kasi-rest-days")).thenReturn(Optional.of(source));
        when(dataGoKrClient.fetchItems(any(), anyMap())).thenReturn(List.of());

        service.sync("kasi-rest-days", null);

        @SuppressWarnings("unchecked")
        var captor = forClass(Map.class);
        verify(dataGoKrClient).fetchItems(any(), captor.capture());
        assertEquals(Map.of(), captor.getValue());
    }
}
