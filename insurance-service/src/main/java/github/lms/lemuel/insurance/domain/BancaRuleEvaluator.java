package github.lms.lemuel.insurance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 방카슈랑스 25%룰 판정 — 순수 도메인 함수.
 *
 * <p><b>규칙</b>: 은행별로, 특정 원수사(보험사) 상품의 신계약 보험료 비중이
 * {@link #CONCENTRATION_LIMIT}(25%)를 <b>초과</b>하면 위반이다 (정확히 25% 는 허용).
 *
 * <p>비중 = 해당 (은행, 원수사) 보험료 합 ÷ 해당 은행의 전체 방카 보험료 합.
 * 비중 스케일은 소수 4자리(HALF_UP) — 0.2500 == 25.00%.
 */
public final class BancaRuleEvaluator {

    /** 방카 25%룰 상한 — 이 값을 초과하면 위반. 단일 선언 지점. */
    public static final BigDecimal CONCENTRATION_LIMIT = new BigDecimal("0.25");

    /** 비중 계산 스케일 (소수 4자리 = bp 단위). */
    public static final int SHARE_SCALE = 4;

    private BancaRuleEvaluator() {
        // static utility
    }

    /**
     * (은행, 원수사)별 보험료 합 목록을 받아 25%룰 위반을 판정한다.
     *
     * @param premiums (은행, 원수사)별 신계약 보험료 합 — 집계 쿼리 결과
     * @return 위반 목록 (없으면 빈 리스트)
     */
    public static List<BancaRuleViolation> evaluate(List<BankInsurerPremium> premiums) {
        Objects.requireNonNull(premiums, "premiums");

        // 은행별 총액 선집계
        Map<String, BigDecimal> bankTotals = new LinkedHashMap<>();
        for (BankInsurerPremium p : premiums) {
            bankTotals.merge(p.bankCode(), p.premiumSum(), BigDecimal::add);
        }

        List<BancaRuleViolation> violations = new ArrayList<>();
        for (BankInsurerPremium p : premiums) {
            BigDecimal bankTotal = bankTotals.get(p.bankCode());
            if (bankTotal.signum() <= 0) {
                continue;
            }
            BigDecimal share = p.premiumSum().divide(bankTotal, SHARE_SCALE, RoundingMode.HALF_UP);
            if (share.compareTo(CONCENTRATION_LIMIT) > 0) {
                violations.add(new BancaRuleViolation(
                        p.bankCode(), p.insurerCode(), share, p.premiumSum(), bankTotal));
            }
        }
        return violations;
    }

    /**
     * 집계 입력 — (은행, 원수사)별 신계약 보험료 합.
     */
    public record BankInsurerPremium(String bankCode, String insurerCode, BigDecimal premiumSum) {

        public BankInsurerPremium {
            Objects.requireNonNull(bankCode, "bankCode");
            Objects.requireNonNull(insurerCode, "insurerCode");
            Objects.requireNonNull(premiumSum, "premiumSum");
        }
    }

    /**
     * 위반 1건 — 은행의 특정 원수사 비중이 상한을 초과.
     *
     * @param share 비중 (소수 4자리, 예: 0.3125 = 31.25%)
     */
    public record BancaRuleViolation(String bankCode, String insurerCode, BigDecimal share,
                                     BigDecimal insurerPremiumSum, BigDecimal bankPremiumTotal) {
    }
}
