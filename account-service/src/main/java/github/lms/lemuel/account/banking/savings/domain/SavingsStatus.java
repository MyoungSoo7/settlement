package github.lms.lemuel.account.banking.savings.domain;

/**
 * 적금 계약 상태.
 *
 * <p>상태는 ACTIVE → CLOSED 단방향이다. 만기해지와 중도해지는 <b>같은 종착 상태</b>를 쓰고
 * 적용 이율(annualRate vs earlyTerminationRate)과 이자 계산 종료일만 달라진다 —
 * 상태를 둘로 쪼개면 "해지된 계약인가?"를 묻는 모든 지점이 두 값을 알아야 한다.
 */
public enum SavingsStatus {
    ACTIVE,
    CLOSED
}
