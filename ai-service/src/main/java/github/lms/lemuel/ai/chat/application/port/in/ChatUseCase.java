package github.lms.lemuel.ai.chat.application.port.in;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 챗봇 대화 1왕복 인바운드 포트.
 *
 * <p>conversationId 가 null 이면 새 대화를 시작하고, 있으면 해당 대화의 최근 컨텍스트를
 * 이어붙여 LLM 을 호출한다. 성공한 왕복만 이력에 저장된다 — LLM 실패 시 사용자 메시지도
 * 저장하지 않아 재전송을 유도한다(설계 §2.4).
 */
public interface ChatUseCase {

    /** 동기 호출 — 완성 응답을 한 번에 반환. */
    ChatResult chat(ChatCommand command);

    /** 스트리밍 호출 — 응답 청크마다 {@code onDelta} 를 부르고, 완료 후 전체 결과를 반환. */
    ChatResult chat(ChatCommand command, Consumer<String> onDelta);

    record ChatCommand(Long userId, UUID conversationId, String message) {
        public ChatCommand {
            if (userId == null) {
                throw new IllegalArgumentException("userId 는 필수입니다");
            }
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("message 는 비어 있을 수 없습니다");
            }
        }
    }

    record ChatResult(UUID conversationId, String reply, String model,
                      Integer inputTokens, Integer outputTokens) {
    }
}
