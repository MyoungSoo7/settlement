package github.lms.lemuel.settlement.domain;

/** 수수료율 정책의 적용 범위. 좁은 것이 넓은 것을 이긴다(SELLER > TIER). */
public enum RateScope {
    /** 특정 셀러와의 개별 계약. */
    SELLER,
    /** 등급 전체에 적용되는 정책. */
    TIER
}
