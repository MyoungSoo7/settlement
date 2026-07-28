package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 금액 반올림 정책 값 객체(불변).
 *
 * <p>통화·상품마다 절사 단위가 다르므로(원화 = 소수점 0자리, 달러 = 2자리) 반올림 규칙을 계산 로직에서
 * 분리했다. {@link RepaymentSchedule} 은 이 정책을 <b>주입받아</b> 회차 금액을 절사하므로, 통화가 바뀌어도
 * 상환 산식(연금공식·잔액 후취 이자·마지막 회차 잔여 흡수)은 수정할 필요가 없다.
 *
 * <p><b>{@code Money} VO 와의 경계</b>: {@code common.money.Money} 는 <em>scale 2 HALF_UP 고정</em>
 * 통화 금액 VO 로, 잔액 차감({@code LoanAdvance#applyRepayment})처럼 정책이 고정된 자리에 쓴다.
 * 이 record 는 그 <em>정책 자체</em>를 값으로 다루는 타입이라 역할이 겹치지 않는다.
 *
 * @param scale 소수점 자릿수 (원화는 0)
 * @param mode  반올림 방식
 */
public record RoundingPolicy(int scale, RoundingMode mode) {

    /** 원화 기본 정책 — 원 단위 정수, HALF_UP. */
    public static final RoundingPolicy KRW = new RoundingPolicy(0, RoundingMode.HALF_UP);

    public RoundingPolicy {
        if (scale < 0) {
            throw new LoanInvariantViolationException("반올림 자릿수는 0 이상이어야 합니다: " + scale);
        }
        if (mode == null) {
            throw new LoanInvariantViolationException("반올림 방식은 필수입니다");
        }
    }

    /** 이 정책의 단위로 금액을 절사한다. */
    public BigDecimal round(BigDecimal value) {
        if (value == null) {
            throw new LoanInvariantViolationException("반올림 대상 금액은 필수입니다");
        }
        return value.setScale(scale, mode);
    }

    /**
     * 반올림 없이 이 정책 단위로 <b>정확히</b> 표현되는 금액인가.
     *
     * <p>계약 원금처럼 사용자가 입력한 값을 조용히 보정하면 안 되는 자리에서 사전 검증에 쓴다
     * (예: 원화 정책에서 {@code 1000000.5} 는 거짓, {@code 1000000.00} 은 참).
     */
    public boolean isExact(BigDecimal value) {
        return value != null && value.stripTrailingZeros().scale() <= scale;
    }

    /** 검증 실패 메시지에 쓰는 사람이 읽는 단위 설명. */
    public String unitDescription() {
        return scale == 0 ? "원 단위 정수" : "소수점 " + scale + "자리";
    }
}
