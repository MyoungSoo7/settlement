package github.lms.lemuel.account.banking.timedeposit.domain;

/**
 * 정기예금 계좌 상태 — 만기 해지·중도 해지를 구분하지 않는 2상태 머신.
 *
 * <p>해지 종류(만기/중도)는 상태가 아니라 <b>적용 이율</b>로만 갈린다. 해지된 계좌는 원리금을 전액
 * 지급해 수신부채가 0 으로 닫히므로, 그 뒤 다시 살아나는 전이가 없다(CLOSED 는 종단 상태).
 */
public enum TimeDepositStatus {

    /** 예치 중 — 해지 가능. */
    ACTIVE,

    /** 해지 완료(만기·중도 공통) — 원리금 지급까지 끝난 종단 상태. 재해지 불가. */
    CLOSED
}
