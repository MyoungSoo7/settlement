package github.lms.lemuel.account.banking.pension.domain;

/**
 * 퇴직연금 계약의 생애주기 상태.
 *
 * <p>전이는 단방향이다: {@code ACCUMULATING → RECEIVING → CLOSED}. 적립 중에만 부담금·중도인출이
 * 성립하고, 수급 개시 후에는 급여 지급만 성립하며, 적립금이 0 이 되는 순간 계약이 닫힌다.
 */
public enum PensionStatus {

    /** 적립 중 — 부담금 납입·운용수익 확정·법정 사유 중도인출이 가능한 구간. */
    ACCUMULATING,

    /** 수급 중 — 연금·일시금 지급과 운용수익 확정만 가능(신규 부담금·중도인출 불가). */
    RECEIVING,

    /** 종료 — 적립금이 0 으로 소진돼 더 이상 어떤 거래도 성립하지 않는다. */
    CLOSED
}
