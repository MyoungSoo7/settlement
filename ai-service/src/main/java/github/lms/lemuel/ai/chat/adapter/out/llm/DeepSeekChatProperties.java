package github.lms.lemuel.ai.chat.adapter.out.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DeepSeek(OpenAI 호환 Chat Completions API) 채팅 연동 설정 (prefix=app.ai.deepseek).
 *
 * <p>★ baseUrl 이 이 어댑터의 존재 이유다 — DeepSeek 은 OpenAI 호환 규격이라
 * {@code POST {baseUrl}/chat/completions} 하나로 <b>원격 DeepSeek API</b>와
 * <b>로컬 Ollama</b>(OpenAI 호환 엔드포인트)를 같은 코드로 부를 수 있다.
 * <ul>
 *   <li>운영: {@code https://api.deepseek.com/v1} + {@code deepseek-chat}(키 필요)</li>
 *   <li>로컬: {@code http://localhost:11434/v1} + {@code deepseek-r1:7b}(Ollama, 키 불필요하지만
 *       {@link #configured()} 계약을 지키려면 임의 문자열 하나를 넣어야 한다)</li>
 * </ul>
 *
 * @param apiKey    DeepSeek 발급 키(Authorization: Bearer) — 미설정이면 채팅 API 503
 * @param model     기본 deepseek-chat (deepseek-reasoner / Ollama 태그로 교체 가능)
 * @param baseUrl   기본 https://api.deepseek.com/v1 (Ollama 로 돌릴 때만 교체)
 * @param maxTokens max_tokens — 응답 길이·비용 상한 (기본 2048)
 */
@ConfigurationProperties(prefix = "app.ai.deepseek")
public record DeepSeekChatProperties(String apiKey, String model, String baseUrl, int maxTokens) {

    public DeepSeekChatProperties {
        if (model == null || model.isBlank()) {
            model = "deepseek-chat";
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.deepseek.com/v1";
        }
        if (maxTokens <= 0) {
            maxTokens = 2048;
        }
        if (apiKey == null) {
            apiKey = "";
        }
    }

    public boolean configured() {
        return !apiKey.isBlank();
    }
}
