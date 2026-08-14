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
    @MockitoBean org.springframework.kafka.config.KafkaListenerEndpointRegistry listenerRegistry;

    /** 실제로 구독 중인 토픽을 들고 있는 컨테이너 하나를 흉내 낸다. */
    private static org.springframework.kafka.listener.MessageListenerContainer container(String... topics) {
        var c = org.mockito.Mockito.mock(org.springframework.kafka.listener.MessageListenerContainer.class);
        when(c.getContainerProperties())
                .thenReturn(new org.springframework.kafka.listener.ContainerProperties(topics));
        return c;
    }

    @Test
    @DisplayName("GET /admin/dlq/topics — 구독 중인 토픽에서 DLT 후보를 만들어 준다")
    void topics() throws Exception {
        // 토픽 이름을 외우게 하면 오타가 곧 사고다 — 실제 구독 목록에서 뽑는다.
        // 컨테이너는 미리 만든다 — when(...) 인자 안에서 스터빙하면 Mockito 가 중첩으로 보고 깨진다.
        var captured = container("lemuel.payment.captured");
        var created = container("lemuel.order.created");
        when(listenerRegistry.getListenerContainers()).thenReturn(List.of(captured, created));

        mockMvc.perform(get("/admin/dlq/topics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sourceTopic").value("lemuel.order.created"))
                .andExpect(jsonPath("$[0].dltTopic").value("lemuel.order.created.DLT"))
                .andExpect(jsonPath("$[1].sourceTopic").value("lemuel.payment.captured"));
    }

    @Test
    @DisplayName("GET /admin/dlq/topics — 같은 토픽을 여러 컨테이너가 구독해도 한 번만 나온다")
    void topicsAreDeduplicated() throws Exception {
        var one = container("lemuel.payment.captured");
        var two = container("lemuel.payment.captured", "lemuel.user.registered");
        when(listenerRegistry.getListenerContainers()).thenReturn(List.of(one, two));

        mockMvc.perform(get("/admin/dlq/topics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("GET /admin/dlq/topics — DLT 토픽 자체는 후보에서 제외한다 (.DLT.DLT 방지)")
    void topicsExcludeDltItself() throws Exception {
        var mixed = container("lemuel.payment.captured", "lemuel.payment.captured.DLT");
        when(listenerRegistry.getListenerContainers()).thenReturn(List.of(mixed));

        mockMvc.perform(get("/admin/dlq/topics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].dltTopic").value("lemuel.payment.captured.DLT"));
    }

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
