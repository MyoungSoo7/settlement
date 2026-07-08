package github.lms.lemuel.commondata.application.service;

import github.lms.lemuel.commondata.application.port.out.LoadDataRecordPort;
import github.lms.lemuel.commondata.application.port.out.LoadDataSourcePort;
import github.lms.lemuel.commondata.domain.DataRecord;
import github.lms.lemuel.commondata.domain.DataSource;
import github.lms.lemuel.commondata.domain.DataSourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataQueryServiceTest {

    @Mock
    private LoadDataSourcePort loadDataSourcePort;

    @Mock
    private LoadDataRecordPort loadDataRecordPort;

    private DataQueryService service;

    private final DataSource source = new DataSource(1L, "kasi-rest-days", "특일정보",
            "https://apis.data.go.kr/x", null, null, 100, true, null, null);

    @BeforeEach
    void setUp() {
        service = new DataQueryService(loadDataSourcePort, loadDataRecordPort);
    }

    @Test
    @DisplayName("소스 목록은 포트 결과를 그대로 반환")
    void getSourcesDelegates() {
        when(loadDataSourcePort.findAll()).thenReturn(List.of(source));

        assertEquals(List.of(source), service.getSources());
    }

    @Test
    @DisplayName("소스 단건 — 없으면 404 예외")
    void getSourceThrowsWhenMissing() {
        when(loadDataSourcePort.findByCode("nope")).thenReturn(Optional.empty());

        assertThrows(DataSourceNotFoundException.class, () -> service.getSource("nope"));
    }

    @Test
    @DisplayName("소스 단건 — 존재하면 반환")
    void getSourceReturns() {
        when(loadDataSourcePort.findByCode("kasi-rest-days")).thenReturn(Optional.of(source));

        assertEquals(source, service.getSource("kasi-rest-days"));
    }

    @Test
    @DisplayName("레코드 조회 — 소스가 없으면 404 예외 (포트 조회 전)")
    void getRecordsThrowsWhenSourceMissing() {
        when(loadDataSourcePort.findByCode("nope")).thenReturn(Optional.empty());

        assertThrows(DataSourceNotFoundException.class, () -> service.getRecords("nope", 10));
    }

    @Test
    @DisplayName("레코드 조회 — limit 0 이하는 기본 100, 상한 500 강제")
    void getRecordsClampsLimit() {
        when(loadDataSourcePort.findByCode(anyString())).thenReturn(Optional.of(source));
        DataRecord record = new DataRecord(1L, "kasi-rest-days", "k", "{}", null);
        when(loadDataRecordPort.findLatest(anyString(), anyInt())).thenReturn(List.of(record));

        assertEquals(List.of(record), service.getRecords("kasi-rest-days", 0));
        verify(loadDataRecordPort).findLatest(eq("kasi-rest-days"), eq(100));

        service.getRecords("kasi-rest-days", 9999);
        verify(loadDataRecordPort).findLatest(eq("kasi-rest-days"), eq(500));

        service.getRecords("kasi-rest-days", 7);
        verify(loadDataRecordPort).findLatest(eq("kasi-rest-days"), eq(7));
    }
}
