package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.SecuredLoanRejectedException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 담보/개인신용 대출 심사 정책 (결정적 · 순수 계산).
 *
 * <p>{@link CorporateCreditPolicy} 와 같은 밴드 테이블 패턴을 쓰되 입력이 다르다 — 기업 신용대출이
 * 재무제표·평판을 점수화하는 반면, 이쪽은 <b>담보가치</b>(담보형)와 <b>외부 CB 점수 스냅샷</b>(신용형)을
 * 근거로 삼는다. 모든 매핑이 구간 기반 결정적 함수라 경계값을 단위테스트로 전수 검증한다.
 *
 * <h4>한도</h4>
 * <ul>
 *   <li>주택담보: {@code 유효담보가치 × LTV}. 유효담보가치 null/≤0 → 한도 0.</li>
 *   <li>개인신용: 담보가 없으므로 <b>CB 등급별 정액 한도표</b>. 미상 등급 → 0(보수적).</li>
 * </ul>
 *
 * <h4>등급 · 금리</h4>
 * <ul>
 *   <li>CB 점수 → 등급: ≥850 A, ≥750 B, ≥650 C, ≥550 D, 미만 E — <b>E 는 대출 불가</b>.</li>
 *   <li>금리 = {@code 기준금리 + 가산금리}. 담보형은 담보가 위험을 흡수하므로 등급과 무관한 고정 가산,
 *       신용형은 등급별 가산.</li>
 * </ul>
 *
 * <p><b>기준금리·LTV 는 주입</b>받는다(운영 중 조정 대상). 등급 밴드·가산금리·정액한도는 정책 상수
 * 테이블로 두어 변경이 데이터 수정 한 곳에 국한되게 한다 — 인라인 계산으로 흩어지면 경계값 테스트를
 * 우회하게 되므로 금지된다.
 */
public class SecuredLoanPolicy {

    /** 담보형 고정 가산금리(%p) — 담보가 위험을 흡수하므로 신용등급과 무관하다. */
    private static final BigDecimal SECURED_SURCHARGE_PERCENT = new BigDecimal("0.8");

    /** 금액·금리 표기 스케일. */
    private static final int SCALE = 2;

    /** CB 점수 → 등급 — 이상 매칭(내림차순). 미매칭(&lt;550) → E. */
    private static final List<Band<String>> CB_GRADE_BANDS = List.of(
            new Band<>("850", "A"), new Band<>("750", "B"), new Band<>("650", "C"), new Band<>("550", "D"));

    /** 등급 → 개인신용 정액 한도. 그 외(E·미상)는 0(대출 불가). */
    private static final Map<String, BigDecimal> PERSONAL_CREDIT_LIMITS = Map.of(
            "A", new BigDecimal("100000000"),
            "B", new BigDecimal("50000000"),
            "C", new BigDecimal("30000000"),
            "D", new BigDecimal("10000000"));

    /** 등급 → 신용형 가산금리(%p). 그 외(E·미상)는 대출 불가라 산정 대상이 아니다. */
    private static final Map<String, BigDecimal> CREDIT_SURCHARGE_PERCENTS = Map.of(
            "A", new BigDecimal("1.5"),
            "B", new BigDecimal("2.5"),
            "C", new BigDecimal("4.0"),
            "D", new BigDecimal("6.0"));

    private final BigDecimal baseRatePercent;
    private final BigDecimal realEstateLtvRatio;

    /**
     * @param baseRatePercent    기준금리(%) — Phase 1 은 설정값, Phase 2 에서 economics-service 연동
     * @param realEstateLtvRatio 주택담보 LTV 비율(소수, 예: 0.70)
     */
    public SecuredLoanPolicy(BigDecimal baseRatePercent, BigDecimal realEstateLtvRatio) {
        this.baseRatePercent = baseRatePercent;
        this.realEstateLtvRatio = realEstateLtvRatio;
    }

    // ─── 등급 ────────────────────────────────────────────────────────────────

    /** 외부 CB 점수 → 등급: ≥850 A, ≥750 B, ≥650 C, ≥550 D, 그 외 E. */
    public String creditGrade(int cbScore) {
        return bandValue(BigDecimal.valueOf(cbScore), CB_GRADE_BANDS, "E");
    }

    /**
     * 대출 불가 등급인지. <b>미상(null)도 차단</b>한다 — 선정산 대출의 평판 haircut 이 데이터 부재를
     * fail-open 으로 다루는 것과 반대인데, 그쪽은 담보(정산예정금)가 이미 있고 평판은 가중치일 뿐인 반면
     * 여기서 CB 등급은 무담보 신용대출의 <em>유일한</em> 심사 근거라 부재를 통과시킬 수 없기 때문이다.
     */
    public boolean isLoanBlocked(String grade) {
        return grade == null || "E".equals(grade);
    }

    // ─── 한도 ────────────────────────────────────────────────────────────────

    /** 주택담보 한도 = 유효담보가치 × LTV. 담보가치 null/≤0 → 0. */
    public BigDecimal mortgageLimit(BigDecimal effectiveCollateralValue, CollateralType type) {
        if (effectiveCollateralValue == null || effectiveCollateralValue.signum() <= 0) {
            return zero();
        }
        return effectiveCollateralValue.multiply(ltvRatio(type)).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** 담보 유형별 LTV 비율. Phase 1 은 부동산 1종이라 주입값을 그대로 쓴다. */
    public BigDecimal ltvRatio(CollateralType type) {
        return realEstateLtvRatio;
    }

    /** 개인신용 한도 — 등급별 정액. 미상/미등록 등급은 0(보수적). */
    public BigDecimal personalCreditLimit(String grade) {
        BigDecimal limit = grade == null ? null : PERSONAL_CREDIT_LIMITS.get(grade);
        return limit == null ? zero() : limit.setScale(SCALE, RoundingMode.HALF_UP);
    }

    // ─── 금리 ────────────────────────────────────────────────────────────────

    /** 적용 연이율(%) = 기준금리 + 가산금리(상품·등급별). */
    public BigDecimal annualRatePercent(LoanProductType productType, String grade) {
        return baseRatePercent.add(surchargePercent(productType, grade))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** 가산금리(%p) — 담보형은 고정, 신용형은 등급별(미상은 0 이나 그 전에 차단된다). */
    private BigDecimal surchargePercent(LoanProductType productType, String grade) {
        if (productType == LoanProductType.MORTGAGE) {
            return SECURED_SURCHARGE_PERCENT;
        }
        BigDecimal surcharge = grade == null ? null : CREDIT_SURCHARGE_PERCENTS.get(grade);
        return surcharge == null ? BigDecimal.ZERO : surcharge;
    }

    // ─── 한도 검증 ────────────────────────────────────────────────────────────

    /**
     * 신청액이 한도 이내인지 검증한다. 초과하면 신청액·한도를 구조적으로 보존한 채 거절한다.
     *
     * @throws SecuredLoanRejectedException 신청액 &gt; 한도
     */
    public void validateWithinLimit(BigDecimal requested, BigDecimal limit) {
        if (requested == null || limit == null || requested.compareTo(limit) > 0) {
            throw new SecuredLoanRejectedException(
                    "신청액이 승인 가능 한도를 초과합니다: 신청 " + requested + " / 한도 " + limit,
                    requested, limit);
        }
    }

    // ─── 밴드 조회 ────────────────────────────────────────────────────────────

    /** 구간 테이블 한 칸: 경계값(이상 매칭)과 매칭 시 부여 값. */
    private record Band<V>(BigDecimal threshold, V value) {
        Band(String threshold, V value) {
            this(new BigDecimal(threshold), value);
        }
    }

    /**
     * 밴드 테이블에서 값에 해당하는 결과를 찾는 유일한 조회 지점(경계값 이상 매칭, 테이블은 내림차순).
     * null 이거나 어떤 밴드에도 걸리지 않으면 {@code defaultValue}(보수적)이다.
     */
    private static <V> V bandValue(BigDecimal value, List<Band<V>> bands, V defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        for (Band<V> band : bands) {
            if (value.compareTo(band.threshold()) >= 0) {
                return band.value();
            }
        }
        return defaultValue;
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
