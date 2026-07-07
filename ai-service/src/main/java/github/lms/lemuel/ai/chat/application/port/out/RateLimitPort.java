package github.lms.lemuel.ai.chat.application.port.out;

/**
 * LLM 비용 가드 아웃바운드 포트 — 사용자별 채팅 호출 상한.
 *
 * <p>LLM 호출 <b>전에</b> 소비해야 한다 (실패한 호출도 비용이 나가므로).
 */
public interface RateLimitPort {

    /** 1회 호출 허가를 소비한다. 상한 초과 시 {@code RateLimitExceededException}. */
    void acquire(Long userId);
}
