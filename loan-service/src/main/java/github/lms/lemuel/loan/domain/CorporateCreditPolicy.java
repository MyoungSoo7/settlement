package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 기업 신용대출 신용평가 정책 (결정적 · 순수 계산).
 *
 * <p>상장사의 최신 요약 재무제표(안정성·수익성) + 뉴스 평판 등급을 0~100 신용점수로 환산하고,
 * 점수 → 등급(A~E) → 한도/수수료를 산정한다. 모든 매핑은 구간(band) 기반 결정적 함수라 단위테스트로
 * 경계값을 전수 검증한다(빌드 시점 회귀 차단).
 *
 * <h4>신용점수(0~100)</h4>
 * <ul>
 *   <li><b>안정성 40점</b> — 부채비율(%): ≤100→40, ≤200→30, ≤300→20, ≤400→10, 초과/자본잠식(null)→0</li>
 *   <li><b>수익성 40점</b> — 영업이익률 구간(0~20) + ROA 구간(0~20)</li>
 *   <li><b>평판 20점</b> — A=20, B=15, C=10, D=5, E=0, 미상(null)=10(중립)</li>
 * </ul>
 *
 * <h4>등급 · 한도 · 수수료</h4>
 * <ul>
 *   <li>등급: ≥80 A, ≥65 B, ≥50 C, ≥35 D, &lt;35 E — <b>E 는 대출 불가</b></li>
 *   <li>한도 = 자본총계 × equityLimitRatio(기본 10%) × gradeFactor(A1.0 B0.8 C0.6 D0.3 E0).
 *       자본총계 null/≤0 → 한도 0</li>
 *   <li>수수료 = 원금 × dailyRate(기존 app.loan.daily-rate) × termDays × gradeSurcharge(A1.0 B1.1 C1.25 D1.5)</li>
 * </ul>
 */
public class CorporateCreditPolicy {

    private final BigDecimal dailyRate;
    private final BigDecimal equityLimitRatio;

    public CorporateCreditPolicy(BigDecimal dailyRate, BigDecimal equityLimitRatio) {
        this.dailyRate = dailyRate;
        this.equityLimitRatio = equityLimitRatio;
    }

    // ─── 구간 테이블 (선언적 밴드) ─────────────────────────────────────────────────
    //
    // 임계값 구간 매핑을 if/else 사슬·switch 대신 (경계값, 결과값) 밴드 테이블 + 단일 조회
    // 메서드({@link #bandValue})로 표현해, 정책 변경을 데이터 수정으로 국한한다(investment 의
    // InvestmentScorePolicy 와 동형). 매칭 방향은 {@link Match} 로 지정한다: 높을수록 고득점 지표는
    // AT_LEAST(경계값 이상, 내림차순), 부채비율처럼 낮을수록 고득점인 지표는 AT_MOST(경계값 이하,
    // 오름차순). 결과 타입이 점수(Integer)·등급(String) 등 다양하므로 밴드는 {@code Band<V>} 로 일반화한다.
    // 미매칭·null → 기본값. 등급 키(A~E) 로 값을 찾는 범주형 매핑은 밴드가 아니라 조회 테이블(Map)이다.

    /** 안정성 40 — 부채비율(%) 이하 매칭(오름차순, 낮을수록 고득점). >400/null → 0. */
    private static final List<Band<Integer>> STABILITY_BANDS = List.of(
            new Band<>("100", 40), new Band<>("200", 30), new Band<>("300", 20), new Band<>("400", 10));

    /** 영업이익률(%) — 이상 매칭(내림차순). 음수/null → 0. */
    private static final List<Band<Integer>> OPERATING_MARGIN_BANDS = List.of(
            new Band<>("20", 20), new Band<>("10", 15), new Band<>("5", 10), new Band<>("0", 5));

    /** ROA(%) — 이상 매칭(내림차순). 음수/null → 0. */
    private static final List<Band<Integer>> ROA_BANDS = List.of(
            new Band<>("10", 20), new Band<>("5", 15), new Band<>("2", 10), new Band<>("0", 5));

    /** 점수 → 등급 — 이상 매칭(내림차순). 미매칭(&lt;35) → E. */
    private static final List<Band<String>> GRADE_BANDS = List.of(
            new Band<>("80", "A"), new Band<>("65", "B"), new Band<>("50", "C"), new Band<>("35", "D"));

    /** 평판 등급 → 점수 조회 테이블. 미상/미등록(null·기타)은 중립 10점. */
    private static final Map<String, Integer> REPUTATION_SCORES = Map.of(
            "A", 20, "B", 15, "C", 10, "D", 5, "E", 0);
    private static final int REPUTATION_NEUTRAL_SCORE = 10;

    /** 등급 → 한도 계수 조회 테이블 — 그 외(E·미상)는 0(대출 불가). */
    private static final Map<String, BigDecimal> GRADE_FACTORS = Map.of(
            "A", BigDecimal.ONE, "B", new BigDecimal("0.8"), "C", new BigDecimal("0.6"), "D", new BigDecimal("0.3"));

    /** 등급 → 수수료 가산 조회 테이블 — 그 외는 1.0(E 는 대출 불가라 산정 대상 아님). */
    private static final Map<String, BigDecimal> GRADE_SURCHARGES = Map.of(
            "A", BigDecimal.ONE, "B", new BigDecimal("1.1"), "C", new BigDecimal("1.25"), "D", new BigDecimal("1.5"));

    // ─── 신용점수 ────────────────────────────────────────────────────────────

    /** 안정성 40점 — 부채비율(%) 구간. null(자본잠식/결측)은 0점. */
    public int stabilityScore(BigDecimal debtRatio) {
        return bandValue(debtRatio, Match.AT_MOST, STABILITY_BANDS, 0);
    }

    /** 수익성 40점 = 영업이익률 구간(0~20) + ROA 구간(0~20). */
    public int profitabilityScore(BigDecimal operatingMargin, BigDecimal roa) {
        return operatingMarginScore(operatingMargin) + roaScore(roa);
    }

    /** 영업이익률(%) 구간: ≥20→20, ≥10→15, ≥5→10, ≥0→5, 음수/null→0. */
    int operatingMarginScore(BigDecimal operatingMargin) {
        return bandValue(operatingMargin, Match.AT_LEAST, OPERATING_MARGIN_BANDS, 0);
    }

    /** ROA(%) 구간: ≥10→20, ≥5→15, ≥2→10, ≥0→5, 음수/null→0. */
    int roaScore(BigDecimal roa) {
        return bandValue(roa, Match.AT_LEAST, ROA_BANDS, 0);
    }

    /** 평판 20점 — A=20 B=15 C=10 D=5 E=0, 미상/미등록=10(중립). */
    public int reputationScore(String reputationGrade) {
        if (reputationGrade == null) {
            return REPUTATION_NEUTRAL_SCORE;
        }
        return REPUTATION_SCORES.getOrDefault(reputationGrade, REPUTATION_NEUTRAL_SCORE);
    }

    /** 세 축 합산 신용점수(0~100). */
    public int creditScore(CorporateFinancials fin, String reputationGrade) {
        return stabilityScore(fin.debtRatio())
                + profitabilityScore(fin.operatingMargin(), fin.roa())
                + reputationScore(reputationGrade);
    }

    /** 점수 → 등급: ≥80 A, ≥65 B, ≥50 C, ≥35 D, 그 외 E. */
    public String creditGrade(int score) {
        return bandValue(BigDecimal.valueOf(score), Match.AT_LEAST, GRADE_BANDS, "E");
    }

    /** E 등급은 대출 불가. */
    public boolean isLoanBlocked(String grade) {
        return "E".equals(grade);
    }

    // ─── 한도 · 수수료 ────────────────────────────────────────────────────────

    /** 등급별 한도 계수 — A1.0 B0.8 C0.6 D0.3 E0(불가). */
    public BigDecimal gradeFactor(String grade) {
        return GRADE_FACTORS.getOrDefault(grade, BigDecimal.ZERO);
    }

    /** 한도 = 자본총계 × equityLimitRatio × gradeFactor. 자본총계 null/≤0 → 0. */
    public BigDecimal creditLimit(BigDecimal totalEquity, String grade) {
        if (totalEquity == null || totalEquity.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return totalEquity.multiply(equityLimitRatio).multiply(gradeFactor(grade))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** 등급별 수수료 가산 — A1.0 B1.1 C1.25 D1.5 (E 는 대출 불가라 산정 대상 아님). */
    public BigDecimal gradeSurcharge(String grade) {
        return GRADE_SURCHARGES.getOrDefault(grade, BigDecimal.ONE);
    }

    /** 수수료 = 원금 × dailyRate × termDays × gradeSurcharge. */
    public BigDecimal fee(BigDecimal principal, int termDays, String grade) {
        if (termDays < 0) {
            throw new LoanInvariantViolationException("대출 기간(일)은 음수일 수 없습니다: " + termDays);
        }
        return principal.multiply(dailyRate)
                .multiply(BigDecimal.valueOf(termDays))
                .multiply(gradeSurcharge(grade))
                .setScale(2, RoundingMode.HALF_UP);
    }

    // ─── 밴드 조회 ────────────────────────────────────────────────────────────

    /** 구간 매칭 방향 — 밴드 경계값을 넘어서는 쪽이 어디인지 정한다. */
    private enum Match {
        /** 값이 경계값 이상이면 매칭(높을수록 고득점). 테이블은 경계값 내림차순. */
        AT_LEAST,
        /** 값이 경계값 이하이면 매칭(낮을수록 고득점). 테이블은 경계값 오름차순. */
        AT_MOST
    }

    /** 구간 테이블 한 칸: 경계값과 매칭 시 부여 값(점수·등급 등). */
    private record Band<V>(BigDecimal threshold, V value) {
        Band(String threshold, V value) {
            this(new BigDecimal(threshold), value);
        }
    }

    /**
     * 밴드 테이블에서 값에 해당하는 결과를 찾는 유일한 조회 지점. 테이블 순서대로 첫 매칭 밴드의 값을
     * 돌려주고, null 이거나 어떤 밴드에도 걸리지 않으면 {@code defaultValue}(보수적)이다.
     */
    private static <V> V bandValue(BigDecimal value, Match match, List<Band<V>> bands, V defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        for (Band<V> band : bands) {
            boolean matched = match == Match.AT_LEAST
                    ? value.compareTo(band.threshold()) >= 0
                    : value.compareTo(band.threshold()) <= 0;
            if (matched) {
                return band.value();
            }
        }
        return defaultValue;
    }
}
