package github.lms.lemuel.settlement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 금액 값 객체(Value Object).
 *
 * <p>정산 도메인의 모든 통화 금액은 원(KRW) 기준 소수점 2자리로 표현된다. 생성 시 scale 2,
 * {@link RoundingMode#HALF_UP} 로 정규화하므로 동등성·반올림 규칙이 한곳에 모인다 — 도메인 곳곳에
 * 흩어져 있던 {@code .setScale(2, HALF_UP)} 를 이 VO 가 캡슐화한다.
 *
 * <p>불변(record)이며 값 기반 동등성을 가진다. 음수도 표현 가능하다(환불로 net 이 0 이하가 되는
 * 중간 계산을 허용) — 음수 여부는 {@link #isNegative()} 로 판별한다.
 */
public record Money(BigDecimal amount) {

    /** 통화 금액 스케일 (원 단위, 소수점 2자리 — DB numeric(.,2) 와 일치). */
    public static final int SCALE = 2;

    public static final Money ZERO = Money.of(BigDecimal.ZERO);

    public Money {
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null");
        }
        amount = amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static Money of(BigDecimal amount) {
        return new Money(amount);
    }

    public static Money of(String amount) {
        return new Money(new BigDecimal(amount));
    }

    /** 정수 원 단위 금액 (예: {@code Money.won(50000)}). */
    public static Money won(long amount) {
        return new Money(BigDecimal.valueOf(amount));
    }

    public Money plus(Money other) {
        return new Money(this.amount.add(other.amount));
    }

    public Money minus(Money other) {
        return new Money(this.amount.subtract(other.amount));
    }

    /** 요율 등 배수 곱셈 (예: 수수료율 0.035). 결과는 scale 2 HALF_UP 로 정규화된다. */
    public Money times(BigDecimal multiplier) {
        if (multiplier == null) {
            throw new IllegalArgumentException("multiplier must not be null");
        }
        return new Money(this.amount.multiply(multiplier));
    }

    public Money min(Money other) {
        return this.amount.compareTo(other.amount) <= 0 ? this : other;
    }

    public Money max(Money other) {
        return this.amount.compareTo(other.amount) >= 0 ? this : other;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isZeroOrNegative() {
        return amount.signum() <= 0;
    }

    public boolean isGreaterThan(Money other) {
        return this.amount.compareTo(other.amount) > 0;
    }

    public boolean isLessThanOrEqualTo(Money other) {
        return this.amount.compareTo(other.amount) <= 0;
    }

    /** 영속성/외부 경계로 내보낼 때 사용하는 원시 표현. */
    public BigDecimal toBigDecimal() {
        return amount;
    }
}
