package github.lms.lemuel.settlement.adapter.in.web.admin;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.config.JacksonCompatConfig;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.settlement.adapter.in.kafka.DlqReplayService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DlqAdminController 는 {@code app.kafka.enabled=true} 일 때만 빈이 생성되므로
 * 테스트 프로퍼티로 명시적으로 활성화한다. 컨트롤러가 요구하는 레거시
 * {@code com.fasterxml.jackson.databind.ObjectMapper} 는 Boot4 슬라이스 테스트에
 * 자동 등록되지 않으므로 {@link JacksonCompatConfig} 를 명시적으로 임포트한다.
 */
@WebMvcTest(controllers = DlqAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "app.kafka.enabled=true")
@Import(JacksonCompatConfig.class)
class DlqAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean DlqReplayService dlqReplayService;
    @MockitoBean AuditLogger auditLogger;

    @Test
    @DisplayName("GET /admin/dlq/inspect — DLT 메시지 인스펙션")
    void inspect() throws Exception {
        DlqReplayService.DlqMessage msg = new DlqReplayService.DlqMessage(
                "lemuel.payment.captured.DLT", 0, 1L,
                "key-1", "{\"x\":1}",
                "lemuel.payment.captured", 5L,
                "org.springframework.kafka.listener.ListenerExecutionFailedException",
                "java.lang.IllegalArgumentException", "boom",
                "event-1", 0);
        when(dlqReplayService.inspect("lemuel.payment.captured.DLT", 20))
                .thenReturn(List.of(msg));

        mockMvc.perform(get("/admin/dlq/inspect").param("topic", "lemuel.payment.captured.DLT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].topic").value("lemuel.payment.captured.DLT"))
                .andExpect(jsonPath("$[0].eventId").value("event-1"));
    }

    @Test
    @DisplayName("GET /admin/dlq/inspect — max 파라미터 전달")
    void inspectWithMax() throws Exception {
        when(dlqReplayService.inspect("lemuel.order.created.DLT", 5)).thenReturn(List.of());

        mockMvc.perform(get("/admin/dlq/inspect")
                        .param("topic", "lemuel.order.created.DLT")
                        .param("max", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("POST /admin/dlq/replay — DLT 메시지 원본 토픽으로 재처리")
    void replay() throws Exception {
        DlqReplayService.ReplayResult result = new DlqReplayService.ReplayResult(
                "lemuel.payment.captured", "lemuel.payment.captured.DLT", 3, 1);
        when(dlqReplayService.replay("lemuel.payment.captured.DLT", 10)).thenReturn(result);

        mockMvc.perform(post("/admin/dlq/replay").param("topic", "lemuel.payment.captured.DLT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceTopic").value("lemuel.payment.captured"))
                .andExpect(jsonPath("$.dltTopic").value("lemuel.payment.captured.DLT"))
                .andExpect(jsonPath("$.sent").value(3))
                .andExpect(jsonPath("$.skipped").value(1));
    }

    @Test
    @DisplayName("POST /admin/dlq/replay — max 파라미터 전달")
    void replayWithMax() throws Exception {
        DlqReplayService.ReplayResult result = new DlqReplayService.ReplayResult(
                "lemuel.order.created", "lemuel.order.created.DLT", 1, 0);
        when(dlqReplayService.replay("lemuel.order.created.DLT", 3)).thenReturn(result);

        mockMvc.perform(post("/admin/dlq/replay")
                        .param("topic", "lemuel.order.created.DLT")
                        .param("max", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(1));
    }
}
