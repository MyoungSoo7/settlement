package github.lms.lemuel.ai.chat.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 대화 메시지 (불변 VO — append-only).
 *
 * <p>model/inputTokens/outputTokens 는 ASSISTANT 메시지에만 존재한다 — 응답을 생성한 모델과
 * 토큰 사용량 스냅샷으로, 추후 사용자/일자별 LLM 비용 집계의 근거가 된다.
 */
public record ChatMessage(
        MessageRole role,
        String content,
        String model,
        Integer inputTokens,
        Integer outputTokens,
        Instant createdAt
) {

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(createdAt, "createdAt");
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("메시지 내용은 비어 있을 수 없습니다");
        }
    }

    /** 사용자 발화 메시지. */
    public static ChatMessage user(String content, Instant at) {
        return new ChatMessage(MessageRole.USER, content, null, null, null, at);
    }

    /** LLM 응답 메시지 — 모델·토큰 usage 를 스냅샷한다. */
    public static ChatMessage assistant(ChatCompletion completion, Instant at) {
        return new ChatMessage(MessageRole.ASSISTANT, completion.text(), completion.model(),
                completion.inputTokens(), completion.outputTokens(), at);
    }
}
