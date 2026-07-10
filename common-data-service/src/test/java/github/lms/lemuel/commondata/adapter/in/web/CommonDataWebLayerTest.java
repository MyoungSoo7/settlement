package github.lms.lemuel.commondata.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.commondata.application.port.in.GetDataRecordsUseCase;
import github.lms.lemuel.commondata.application.port.in.GetDataSourcesUseCase;
import github.lms.lemuel.commondata.application.port.in.RegisterDataSourceUseCase;
import github.lms.lemuel.commondata.application.port.in.SyncDataSourceUseCase;
import github.lms.lemuel.commondata.application.port.in.SyncResult;
import github.lms.lemuel.commondata.domain.DataRecord;
import github.lms.lemuel.commondata.domain.DataSource;
import github.lms.lemuel.commondata.domain.DataSourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CommonDataWebLayerTest {

    private final GetDataSourcesUseCase getSources = mock(GetDataSourcesUseCase.class);
    private final GetDataRecordsUseCase getRecords = mock(GetDataRecordsUseCase.class);
    private final RegisterDataSourceUseCase register = mock(RegisterDataSourceUseCase.class);
    private final SyncDataSourceUseCase sync = mock(SyncDataSourceUseCase.class);
    private final SyncStatusTracker tracker = new SyncStatusTracker();
    private final TaskExecutor inlineExecutor = Runnable::run;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final DataSource source = new DataSource(1L, "kasi-rest-days", "특일정보", "https://x.test",
            Map.of("_type", "json"), List.of("locdate"), 100, true, "공휴일", Instant.now());

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        DataSourceController sourceController = new DataSourceController(getSources, getRecords, objectMapper);
        CommonDataAdminController adminController =
                new CommonDataAdminController(register, sync, tracker, inlineExecutor);
        mvc = MockMvcBuilders.standaloneSetup(sourceController, adminController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 소스_목록_조회() throws Exception {
        when(getSources.getSources()).thenReturn(List.of(source));

        mvc.perform(get("/api/common-data/sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("kasi-rest-days"))
                .andExpect(jsonPath("$[0].defaultParams._type").value("json"));
    }

    @Test
    void 소스_단건_조회() throws Exception {
        when(getSources.getSource("kasi-rest-days")).thenReturn(source);

        mvc.perform(get("/api/common-data/sources/kasi-rest-days"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("kasi-rest-days"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void 없는소스_조회는_404() throws Exception {
        when(getSources.getSource("nope")).thenThrow(new DataSourceNotFoundException("nope"));

        mvc.perform(get("/api/common-data/sources/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 레코드_조회는_payload를_객체로_되살린다() throws Exception {
        DataRecord json = new DataRecord(1L, "kasi-rest-days", "20260101",
                "{\"dateName\":\"신정\",\"locdate\":\"20260101\"}", Instant.now());
        DataRecord raw = new DataRecord(2L, "kasi-rest-days", "raw", "not-json", Instant.now());
        when(getRecords.getRecords(eq("kasi-rest-days"), anyInt())).thenReturn(List.of(json, raw));

        mvc.perform(get("/api/common-data/sources/kasi-rest-days/records").param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceCode").value("kasi-rest-days"))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.records[0].data.dateName").value("신정"))
                .andExpect(jsonPath("$.records[1].data").value("not-json"));   // 파싱 실패 → 원문 문자열
    }

    @Test
    void 소스_등록() throws Exception {
        when(register.register(any())).thenReturn(source);
        String body = """
                {"code":"kasi-rest-days","name":"특일정보","endpoint":"https://x.test",
                 "defaultParams":{"_type":"json"},"keyFields":["locdate"],
                 "pageSize":100,"enabled":true,"description":"공휴일"}""";

        mvc.perform(post("/admin/commondata/sources").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("kasi-rest-days"));
    }

    @Test
    void 수집트리거는_202_이후_status는_DONE() throws Exception {
        when(sync.sync(eq("kasi-rest-days"), any())).thenReturn(new SyncResult(5, 5, 0, 0));

        mvc.perform(post("/admin/commondata/sources/kasi-rest-days/sync").param("solYear", "2027"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.statusUrl").value("/admin/commondata/sync/status"));

        assertThat(tracker.current().state()).isEqualTo(SyncStatusTracker.State.DONE);

        mvc.perform(get("/admin/commondata/sync/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DONE"));
    }

    @Test
    void 수집중이면_409() throws Exception {
        tracker.tryStart("sync:existing");

        mvc.perform(post("/admin/commondata/sources/kasi-rest-days/sync"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 수집실패는_status가_FAILED() throws Exception {
        when(sync.sync(eq("kasi-rest-days"), any())).thenThrow(new IllegalStateException("boom"));

        mvc.perform(post("/admin/commondata/sources/kasi-rest-days/sync"))
                .andExpect(status().isAccepted());

        assertThat(tracker.current().state()).isEqualTo(SyncStatusTracker.State.FAILED);
        assertThat(tracker.current().error()).isEqualTo("boom");
    }

    @Test
    void syncStatusTracker_State_enum() {
        assertThat(SyncStatusTracker.State.valueOf("RUNNING")).isEqualTo(SyncStatusTracker.State.RUNNING);
        assertThat(SyncStatusTracker.State.values()).hasSize(4);
    }
}
