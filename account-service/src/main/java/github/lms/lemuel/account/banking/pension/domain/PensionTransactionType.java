package github.lms.lemuel.account.banking.pension.domain;

/**
 * 퇴직연금 서브원장 거래 종류 — GL 전기 팩토리와 1:1 대응한다.
 *
 * <pre>
 * CONTRIBUTION   → AccountEntry.pensionContributionPaid  (적립금 증가)
 * INTEREST       → AccountEntry.pensionInterestSettled   (적립금 증가)
 * BENEFIT        → AccountEntry.pensionBenefitPaid       (적립금 감소)
 * MID_WITHDRAWAL → AccountEntry.pensionMidWithdrawn      (적립금 감소)
 * </pre>
 */
public enum PensionTransactionType {

    /** 부담금 납입 — 납입 주체(ContributionSource)를 반드시 동반한다. */
    CONTRIBUTION,

    /** 운용수익(원리금보장 이자) 확정. */
    INTEREST,

    /** 퇴직급여 지급(연금·일시금 공통). */
    BENEFIT,

    /** 법정 사유 중도인출 — 인출 사유(MidWithdrawalReason)를 반드시 동반한다. */
    MID_WITHDRAWAL
}
