package github.lms.lemuel.loan.application.service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 선정산 대출 한도·수수료 정책 (정산예정금 기반 + 평판 리스크 반영).
 *
 * <p>모델:
 * <ul>
 *   <li>한도 = 셀러 미지급 정산예정금 합계 × LTV × <b>평판 haircut(등급)</b></li>
 *   <li>수수료 = 선지급액 × 일할이율 × 선지급일수(정산예정일까지의 days)</li>
 * </ul>
 *
 * <p><b>평판 haircut</b>(ADR 0023 Phase 3 후속): 셀러(법인)의 뉴스 평판 등급이 나쁠수록 한도를 깎는다.
 * 기본값 A·B=1.0(무변동), C=0.85, D=0.70, E=0.0(차단). 등급을 모르면(매핑/이벤트 미수신) 1.0 —
 * 평판 데이터 부재가 대출을 막지 않는다(fail-open). 요율은 {@code app.loan.reputation.*} 로 주입.
 *
 * <p>정책 파라미터는 불변 — 이력 재현성을 위해 정산의 commission_rate 스냅샷과 같은 철학이나,
 * 대출은 신청 시점의 한도로 즉시 검증하므로 스냅샷 보존은 상위(LoanAdvance)에서 다룬다.
 */
public class CreditPolicy {

    private final BigDecimal ltv;
    private final BigDecimal dailyRate;
    private final Map<String, BigDecimal> reputationHaircut;

    public CreditPolicy(BigDecimal ltv, BigDecimal dailyRate, Map<String, BigDecimal> reputationHaircut) {
        this.ltv = ltv;
        this.dailyRate = dailyRate;
        this.reputationHaircut = Map.copyOf(reputationHaircut);
    }

    /** 등급별 haircut 계수 — 미상/미등록 등급은 1.0(무변동, fail-open). */
    public BigDecimal haircutFor(String grade) {
        if (grade == null) {
            return BigDecimal.ONE;
        }
        return reputationHaircut.getOrDefault(grade, BigDecimal.ONE);
    }

    /** 평판 미반영 한도(정산예정금 × LTV). */
    public BigDecimal creditLimit(BigDecimal unpaidSettlementTotal) {
        return unpaidSettlementTotal.multiply(ltv);
    }

    /** 평판 등급 반영 한도(= 기본 한도 × haircut). */
    public BigDecimal creditLimit(BigDecimal unpaidSettlementTotal, String grade) {
        return creditLimit(unpaidSettlementTotal).multiply(haircutFor(grade));
    }

    /** 선지급액과 선지급일수로 수수료를 산정한다. */
    public BigDecimal fee(BigDecimal principal, int days) {
        if (days < 0) {
            throw new IllegalArgumentException("선지급일수는 음수일 수 없습니다: " + days);
        }
        return principal.multiply(dailyRate).multiply(BigDecimal.valueOf(days));
    }

    /** 신청액이 평판 반영 한도를 초과하면 예외. */
    public void validateWithinLimit(BigDecimal requested, BigDecimal unpaidSettlementTotal, String grade) {
        BigDecimal limit = creditLimit(unpaidSettlementTotal, grade);
        if (requested.compareTo(limit) > 0) {
            String reason = "E".equals(grade)
                    ? " (평판 등급 E — 선정산 대출 차단)"
                    : (grade != null && haircutFor(grade).compareTo(BigDecimal.ONE) < 0
                        ? " (평판 등급 " + grade + " haircut 적용)"
                        : "");
            throw new IllegalArgumentException(
                    "신청액이 한도를 초과합니다. requested=" + requested + ", limit=" + limit + reason);
        }
    }
}
