package github.lms.lemuel.ai.chat.application.port.out;

/**
 * LLM 비용 가드 아웃바운드 포트 — 사용자별 채팅 호출 상한.
 *
 * <p>LLM 호출 <b>전에</b> 소비해야 한다 (실패한 호출도 비용이 나가므로).
 */
public interface RateLimitPort {

    /** 1회 호출 허가를 소비한다. 상한 초과 시 {@code RateLimitExceededException}. */
    void acquire(Long userId);

    /**
     * 소비했던 1회 허가를 되돌린다 — LLM 호출이 <b>과금 없이</b> 실패한 경우에만 호출.
     * AI 장애 중 비용 없는 실패로 사용자 쿼터가 소진되는 것을 막는다(best-effort, 없는 버킷은 무시).
     */
    void refund(Long userId);
}
