package github.lms.lemuel.ai.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 챗봇 설정 (prefix=app.ai.chat) — @ConfigurationPropertiesScan(github.lms.lemuel.ai) 으로 등록.
 *
 * <p>apiKey/model/maxTokens/timeout 은 LLM 어댑터가, historyWindow/systemPrompt 는
 * ChatService(컨텍스트 윈도 구성)가 사용한다. config 는 조립 계층이라 application 에서
 * 주입해도 헥사고날 의존 방향을 깨지 않는다(operation 의 OpsProperties 선례).
 *
 * <p>{@code @Validated} — 잘못된 수치 설정(historyWindow=0 → PageRequest 예외 등)을
 * 런타임 500 이 아니라 <b>부팅 시점</b>에 차단한다. apiKey 는 미설정(blank)이 정상 상태이므로
 * (키 없으면 채팅만 503) 검증하지 않는다.
 */
@Validated
@ConfigurationProperties(prefix = "app.ai.chat")
public record AiChatProperties(
        String apiKey,
        String model,
        @Min(1) int maxTokens,
        @Min(1) int historyWindow,
        @Min(1) int timeoutSeconds,
        String systemPrompt
) {

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
