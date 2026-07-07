package github.lms.lemuel.ai.chat.domain;

/**
 * LLM 호출 1회의 결과 (불변 VO).
 *
 * <p>토큰 usage 는 제공자가 응답에 싣지 않는 경우(스트리밍 중단 등) null 일 수 있다.
 */
public record ChatCompletion(
        String text,
        String model,
        Integer inputTokens,
        Integer outputTokens
) {

    public ChatCompletion {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("LLM 응답 텍스트는 비어 있을 수 없습니다");
        }
    }
}
