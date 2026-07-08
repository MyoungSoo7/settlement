package github.lms.lemuel.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 챗봇 설정 (prefix=app.ai.chat) — @ConfigurationPropertiesScan(github.lms.lemuel.ai) 으로 등록.
 *
 * <p>apiKey/model/maxTokens/timeout 은 LLM 어댑터가, historyWindow/systemPrompt 는
 * ChatService(컨텍스트 윈도 구성)가 사용한다. config 는 조립 계층이라 application 에서
 * 주입해도 헥사고날 의존 방향을 깨지 않는다(operation 의 OpsProperties 선례).
 */
@ConfigurationProperties(prefix = "app.ai.chat")
public record AiChatProperties(
        String apiKey,
        String model,
        int maxTokens,
        int historyWindow,
        int timeoutSeconds,
        String systemPrompt
) {

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
