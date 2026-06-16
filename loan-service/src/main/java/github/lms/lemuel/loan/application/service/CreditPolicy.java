package github.lms.lemuel.loan.application.service;

import java.math.BigDecimal;

/**
 * 선정산 대출 한도·수수료 정책 (정산예정금 기반 단순 모델).
 *
 * <p>1차 모델:
 * <ul>
 *   <li>한도 = 셀러 미지급 정산예정금 합계 × LTV</li>
 *   <li>수수료 = 선지급액 × 일할이율 × 선지급일수(정산예정일까지의 days)</li>
 * </ul>
 *
 * <p>신용등급 스코어링·이자제한법 검증 등은 확장 지점(본 클래스를 교체/확장)으로 남긴다.
 * 정책 파라미터(LTV, 일할이율)는 {@code app.loan.*} 설정에서 주입된다.
 */
public class CreditPolicy {

    private final BigDecimal ltv;
    private final BigDecimal dailyRate;

    public CreditPolicy(BigDecimal ltv, BigDecimal dailyRate) {
        this.ltv = ltv;
        this.dailyRate = dailyRate;
    }

    /** 셀러 미지급 정산예정금 합계로부터 대출 한도를 산정한다. */
    public BigDecimal creditLimit(BigDecimal unpaidSettlementTotal) {
        return unpaidSettlementTotal.multiply(ltv);
    }

    /** 선지급액과 선지급일수로 수수료를 산정한다. */
    public BigDecimal fee(BigDecimal principal, int days) {
        if (days < 0) {
            throw new IllegalArgumentException("선지급일수는 음수일 수 없습니다: " + days);
        }
        return principal.multiply(dailyRate).multiply(BigDecimal.valueOf(days));
    }

    /** 신청액이 한도를 초과하면 예외. */
    public void validateWithinLimit(BigDecimal requested, BigDecimal unpaidSettlementTotal) {
        if (requested.compareTo(creditLimit(unpaidSettlementTotal)) > 0) {
            throw new IllegalArgumentException(
                    "신청액이 한도를 초과합니다. requested=" + requested
                            + ", limit=" + creditLimit(unpaidSettlementTotal));
        }
    }
}
