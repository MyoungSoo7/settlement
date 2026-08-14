package github.lms.lemuel.deposit.domain;

/**
 * 예치금 상계 부족분 상태.
 */
public enum DepositShortfallStatus {
    /** 부족분 발생 — 아직 미해소. */
    OPEN,
    /** 재상계 배치에 의해 전액 해소됨. */
    RESOLVED,
    /** 회수 불가로 상각 처리됨. */
    WRITTEN_OFF
}
