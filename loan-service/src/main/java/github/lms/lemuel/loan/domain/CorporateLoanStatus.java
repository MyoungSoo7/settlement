package github.lms.lemuel.loan.domain;

/**
 * 기업 신용대출(CorporateLoan) 생명주기.
 *
 * <pre>
 * REQUESTED → APPROVED → DISBURSED → REPAID
 *           ↘ REJECTED
 * </pre>
 *
 * <p>선정산 대출(LoanAdvance)이 정산예정금을 담보로 하는 것과 달리, 기업 신용대출은 상장사의
 * 재무제표·평판으로 산정한 신용점수/등급 기반 한도로 심사한다(무담보 신용). 상환은 셀러 정산 saga 가
 * 아니라 명시적 {@code repay(amount)} 로 미상환잔액을 차감한다.
 */
public enum CorporateLoanStatus {
    REQUESTED,
    APPROVED,
    DISBURSED,
    REPAID,
    REJECTED
}
