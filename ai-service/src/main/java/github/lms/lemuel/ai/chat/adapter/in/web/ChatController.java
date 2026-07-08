package github.lms.lemuel.ai.chat.adapter.in.web;

import github.lms.lemuel.ai.chat.adapter.in.web.dto.ChatDtos.ChatRequest;
import github.lms.lemuel.ai.chat.adapter.in.web.dto.ChatDtos.ChatResponse;
import github.lms.lemuel.ai.chat.application.port.in.ChatUseCase;
import github.lms.lemuel.ai.chat.application.port.in.ChatUseCase.ChatCommand;
import github.lms.lemuel.ai.chat.application.port.in.ChatUseCase.ChatResult;
import github.lms.lemuel.ai.config.AiChatProperties;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 챗봇 대화 API (설계 §5.1).
 *
 * <ul>
 *   <li>POST /api/ai/chat — 동기: 완성 응답 한 번에</li>
 *   <li>POST /api/ai/chat/stream — SSE: delta 이벤트 반복 후 done 이벤트(전체 결과)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatUseCase chatUseCase;
    private final AiChatProperties properties;

    public ChatController(ChatUseCase chatUseCase, AiChatProperties properties) {
        this.chatUseCase = chatUseCase;
        this.properties = properties;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, Authentication authentication) {
        Long userId = AuthenticatedUser.userId(authentication);
        ChatResult result = chatUseCase.chat(new ChatCommand(userId, request.conversationId(), request.message()));
        return ChatResponse.from(result);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request, Authentication authentication) {
        Long userId = AuthenticatedUser.userId(authentication);   // 스레드 전환 전에 확정
        ChatCommand command = new ChatCommand(userId, request.conversationId(), request.message());

        // LLM timeout(30s) + 저장 여유. 가상 스레드로 요청 스레드를 붙들지 않는다.
        SseEmitter emitter = new SseEmitter((properties.timeoutSeconds() + 30) * 1000L);
        Thread.startVirtualThread(() -> {
            try {
                ChatResult result = chatUseCase.chat(command, delta -> {
                    try {
                        emitter.send(SseEmitter.event().name("delta").data(delta));
                    } catch (IOException e) {
                        // 클라이언트 이탈 — 스트림 중단 (미완료 왕복은 저장되지 않는다)
                        throw new UncheckedIOException(e);
                    }
                });
                emitter.send(SseEmitter.event().name("done").data(ChatResponse.from(result)));
                emitter.complete();
            } catch (UncheckedIOException clientGone) {
                log.debug("SSE 클라이언트 이탈 — 스트림 중단");
                emitter.completeWithError(clientGone);
            } catch (Exception e) {
                // 스트림 도중 실패 — error 이벤트로 사유 전달 후 종료 (HTTP 상태는 이미 200)
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage() == null ? "AI 응답 실패" : e.getMessage()));
                } catch (IOException ignored) {
                    // 전송조차 불가하면 그대로 종료
                }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
