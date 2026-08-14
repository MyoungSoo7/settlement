package github.lms.lemuel.account.banking.savings.domain;

/**
 * 적금 적립 방식.
 *
 * <p>이 enum 이 곧 회차 납입 검증 규칙의 분기점이다 — 두 방식은 "회차당 얼마를 받을 수 있는가"만 다르고
 * 이자 계산(회차별 일수 가중)은 완전히 동일하다.
 */
public enum SavingsType {

    /** 정액적립식(정기적금) — 모든 회차를 {@code monthlyAmount} 와 <b>정확히 같은 금액</b>으로만 납입한다. */
    FIXED,

    /** 자유적립식 — 회차 금액이 자유다. {@code paymentLimit} 가 있으면 회차당 그 한도까지만 받는다. */
    FLEXIBLE
}
