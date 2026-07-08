package github.lms.lemuel.ai.chat.application.port.out;

import github.lms.lemuel.ai.chat.domain.ChatCompletion;
import github.lms.lemuel.ai.chat.domain.ChatMessage;

import java.util.List;
import java.util.function.Consumer;

/**
 * LLM 호출 아웃바운드 포트 — ★ Spring AI(벤더) 격리 지점.
 *
 * <p>application 계층은 이 인터페이스만 알고, Spring AI/Anthropic 타입은
 * {@code adapter/out/llm} 안에만 존재한다(ArchUnit 강제). 어댑터 교체(직접 RestClient,
 * 다른 프로바이더)가 계약 변경 없이 가능해야 한다(설계 §2.1).
 */
public interface ChatCompletionPort {

    /** API 키가 설정되어 호출 가능한 상태인지. false 면 채팅은 503, 이력 조회는 정상. */
    boolean isConfigured();

    /** 동기 완성 호출. 실패 시 {@code AiUnavailableException}. */
    ChatCompletion complete(String systemPrompt, List<ChatMessage> history, String userMessage);

    /** 스트리밍 호출 — 텍스트 청크마다 {@code onDelta} 콜백 후 전체 결과 반환. */
    ChatCompletion stream(String systemPrompt, List<ChatMessage> history, String userMessage,
                          Consumer<String> onDelta);
}
