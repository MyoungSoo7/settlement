package github.lms.lemuel.ai.chat.application.exception;

/**
 * LLM 호출 실패 (타임아웃·5xx·파싱 실패 등) — 폴백 없이 명시적 실패(설계 §2.4).
 * 이 예외가 나면 해당 왕복은 이력에 저장되지 않는다.
 */
public class AiUnavailableException extends RuntimeException {

    public AiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
