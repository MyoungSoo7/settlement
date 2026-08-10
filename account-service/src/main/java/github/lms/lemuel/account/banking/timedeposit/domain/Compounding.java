package github.lms.lemuel.account.banking.timedeposit.domain;

/**
 * 정기예금 이자 계산 방식.
 *
 * <p>어느 쪽이든 일수 계산 기준은 ACT/365 로 동일하며, 차이는 원금에 이자가 재투입되는지 여부뿐이다.
 * 이자는 만기·해지 <b>시점에 한 번만</b> 확정하므로(주기 accrual 없음) 이 enum 은 확정 시점의
 * 계산식을 고르는 역할만 한다 — 상태를 갖지 않는다.
 */
public enum Compounding {

    /** 단리 — {@code 원금 × 이율 × 경과일수 / 365}. 원금은 만기까지 불변이다. */
    SIMPLE,

    /**
     * 월복리 — 경과한 <b>완전한 개월</b> 수만큼 월이율({@code 연이율/12})로 복리 적립한 뒤,
     * 남는 자투리 일수는 같은 이율의 단리로 덧붙인다(월 미만 구간은 복리 회차를 만들지 않는다).
     */
    MONTHLY_COMPOUND
}
