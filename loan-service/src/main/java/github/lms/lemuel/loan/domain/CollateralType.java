package github.lms.lemuel.loan.domain;

/**
 * 담보 유형. Phase 1 은 주택담보(부동산)만 다룬다 — 보증기관 보증서·금융자산 담보는 Phase 2 이월.
 */
public enum CollateralType {
    /** 부동산(주택) 담보. */
    REAL_ESTATE
}
