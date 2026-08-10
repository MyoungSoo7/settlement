package github.lms.lemuel.settlement.domain;

/**
 * 적용된 요율이 어디서 왔는지 — 정산 문의 시 "왜 이 요율인가"에 답하기 위한 근거.
 * {@code settlements.commission_rate_source} 로 영구 보존된다.
 */
public enum RateSource {
    SELLER,
    TIER,
    /** 정책 미매칭 — 등급 enum 기본율. */
    DEFAULT_TIER
}
