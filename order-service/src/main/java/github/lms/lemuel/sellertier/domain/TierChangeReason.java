package github.lms.lemuel.sellertier.domain;

/** 등급이 바뀐 사유 — 이력에 남아 "누가 왜 바꿨나"를 설명한다. */
public enum TierChangeReason {
    AUTO_PROMOTION,
    AUTO_DEMOTION,
    ADMIN_OVERRIDE
}
