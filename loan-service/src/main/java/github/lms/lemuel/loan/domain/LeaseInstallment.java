package github.lms.lemuel.loan.domain;

import java.math.BigDecimal;

/**
 * 리스·할부 스케줄의 한 회차. 전 금액은 원(KRW) 단위 정수 스케일 {@link BigDecimal}.
 *
 * <p>대출 회차({@link RepaymentInstallment})와 구조는 같지만 <b>마지막 회차의 잔액이 0 이 아닐 수 있다</b> —
 * 리스는 잔존가치를 남기고 끝나기 때문이다. 그 잔액이 만기 시점의 인수가(금융리스)이거나
 * 반환 물건의 장부가(운용리스)가 된다.
 *
 * @param installmentNo    회차(1-base)
 * @param principalPortion 이번 회차가 회수한 원금
 * @param interest         이번 회차 이자(직전 잔액 × 월이율)
 * @param rental           이번 회차 리스료·할부금(= principalPortion + interest)
 * @param remainingBalance 이번 회차 후 남은 잔액 — 만기 회차에서는 잔존가치와 일치한다
 */
public record LeaseInstallment(
        int installmentNo,
        BigDecimal principalPortion,
        BigDecimal interest,
        BigDecimal rental,
        BigDecimal remainingBalance) {
}
