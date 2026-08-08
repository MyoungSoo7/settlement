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
 * <p><b>규칙</b>: 은행별·<b>부문(생보/손보)별</b>로, 특정 원수사(보험사) 상품의 신계약 보험료
 * 비중이 {@link #CONCENTRATION_LIMIT}(25%)를 <b>초과</b>하면 위반이다 (정확히 25% 는 허용).
 *
 * <p>비중 = 해당 (은행, 부문, 원수사) 보험료 합 ÷ 해당 (은행, 부문)의 방카 보험료 합.
 * 비중 스케일은 소수 4자리(HALF_UP) — 0.2500 == 25.00%.
 *
 * <p><b>적용 대상</b>: 자산총액 {@link #ASSET_APPLICABILITY_THRESHOLD}(2조원) <b>이상</b>인
 * 은행만 룰 적용 대상이다(경계 포함). 자산이 등록되지 않은 은행은 <b>적용 대상으로 본다</b>
 * (fail-closed) — 모니터링에서 위반 누락이 면제 오탐보다 나쁘다.
 */
public final class BancaRuleEvaluator {

    /** 방카 25%룰 상한 — 이 값을 초과하면 위반. 단일 선언 지점. */
    public static final BigDecimal CONCENTRATION_LIMIT = new BigDecimal("0.25");

    /** 룰 적용 자산총액 하한(원) — 2조원 이상이면 적용 대상(경계 포함). 단일 선언 지점. */
    public static final BigDecimal ASSET_APPLICABILITY_THRESHOLD = new BigDecimal("2000000000000");

    /** 비중 계산 스케일 (소수 4자리 = bp 단위). */
    public static final int SHARE_SCALE = 4;

    private BancaRuleEvaluator() {
        // static utility
    }

    /**
     * (은행, 부문, 원수사)별 보험료 합 목록을 받아 25%룰 위반을 판정한다.
     *
     * @param premiums        (은행, 부문, 원수사)별 신계약 보험료 합 — 집계 쿼리 결과
     * @param bankTotalAssets 은행 코드 → 자산총액. 등록돼 있고 2조 미만이면 그 은행은 면제,
     *                        미등록이면 적용 대상(fail-closed)
     * @return 위반 목록 (없으면 빈 리스트)
     */
    public static List<BancaRuleViolation> evaluate(
            List<BankInsurerPremium> premiums, Map<String, BigDecimal> bankTotalAssets) {
        Objects.requireNonNull(premiums, "premiums");
        Objects.requireNonNull(bankTotalAssets, "bankTotalAssets");

        // (은행, 부문)별 총액 선집계 — 비중의 분모. 은행 전체 합산이면 부문 내 집중이 희석된다.
        Map<PoolKey, BigDecimal> poolTotals = new LinkedHashMap<>();
        for (BankInsurerPremium p : premiums) {
            poolTotals.merge(new PoolKey(p.bankCode(), p.sector()), p.premiumSum(), BigDecimal::add);
        }

        List<BancaRuleViolation> violations = new ArrayList<>();
        for (BankInsurerPremium p : premiums) {
            if (!isSubjectToRule(bankTotalAssets.get(p.bankCode()))) {
                continue;   // 자산 2조 미만 등록 은행 — 룰 적용 면제
            }
            BigDecimal poolTotal = poolTotals.get(new PoolKey(p.bankCode(), p.sector()));
            if (poolTotal.signum() <= 0) {
                continue;
            }
            BigDecimal share = p.premiumSum().divide(poolTotal, SHARE_SCALE, RoundingMode.HALF_UP);
            if (share.compareTo(CONCENTRATION_LIMIT) > 0) {
                violations.add(new BancaRuleViolation(
                        p.bankCode(), p.sector(), p.insurerCode(), share, p.premiumSum(), poolTotal));
            }
        }
        return violations;
    }

    /** 비중 풀 식별자 — 판정 단위는 (은행, 부문)이다. */
    private record PoolKey(String bankCode, InsurerSector sector) {
    }

    /**
     * 자산총액 기준 룰 적용 대상 판정 — 2조원 이상이면 적용(경계 포함).
     *
     * @param totalAssetsOrNull 자산총액. {@code null}(미등록)이면 적용 대상으로 본다(fail-closed)
     */
    public static boolean isSubjectToRule(BigDecimal totalAssetsOrNull) {
        return totalAssetsOrNull == null
                || totalAssetsOrNull.compareTo(ASSET_APPLICABILITY_THRESHOLD) >= 0;
    }

    /**
     * 집계 입력 — (은행, 부문, 원수사)별 신계약 보험료 합.
     */
    public record BankInsurerPremium(
            String bankCode, InsurerSector sector, String insurerCode, BigDecimal premiumSum) {

        public BankInsurerPremium {
            Objects.requireNonNull(bankCode, "bankCode");
            Objects.requireNonNull(sector, "sector");
            Objects.requireNonNull(insurerCode, "insurerCode");
            Objects.requireNonNull(premiumSum, "premiumSum");
        }
    }

    /**
     * 위반 1건 — 은행의 특정 부문에서 특정 원수사 비중이 상한을 초과.
     *
     * @param share              비중 (소수 4자리, 예: 0.3125 = 31.25%)
     * @param sectorPremiumTotal 분모 — 해당 (은행, 부문)의 방카 신계약 보험료 총액
     */
    public record BancaRuleViolation(String bankCode, InsurerSector sector, String insurerCode,
                                     BigDecimal share, BigDecimal insurerPremiumSum,
                                     BigDecimal sectorPremiumTotal) {
    }
}
