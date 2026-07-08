package github.lms.lemuel.ai.chat.application.exception;

/** ANTHROPIC_API_KEY 미설정 — 부팅·이력 조회는 정상이나 채팅은 불가(503 안내). */
public class AiNotConfiguredException extends RuntimeException {

    public AiNotConfiguredException() {
        super("AI 챗봇이 아직 구성되지 않았습니다 (API 키 미설정)");
    }
}
