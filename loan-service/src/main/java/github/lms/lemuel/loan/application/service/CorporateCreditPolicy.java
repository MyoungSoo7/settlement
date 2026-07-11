package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.domain.CorporateFinancials;

import java.math.BigDecimal;
import java.math.RoundingMode;

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

    // ─── 신용점수 ────────────────────────────────────────────────────────────

    /** 안정성 40점 — 부채비율(%) 구간. null(자본잠식/결측)은 0점. */
    public int stabilityScore(BigDecimal debtRatio) {
        if (debtRatio == null) {
            return 0;
        }
        if (debtRatio.compareTo(BigDecimal.valueOf(100)) <= 0) return 40;
        if (debtRatio.compareTo(BigDecimal.valueOf(200)) <= 0) return 30;
        if (debtRatio.compareTo(BigDecimal.valueOf(300)) <= 0) return 20;
        if (debtRatio.compareTo(BigDecimal.valueOf(400)) <= 0) return 10;
        return 0;
    }

    /** 수익성 40점 = 영업이익률 구간(0~20) + ROA 구간(0~20). */
    public int profitabilityScore(BigDecimal operatingMargin, BigDecimal roa) {
        return operatingMarginScore(operatingMargin) + roaScore(roa);
    }

    /** 영업이익률(%) 구간: ≥20→20, ≥10→15, ≥5→10, ≥0→5, 음수/null→0. */
    int operatingMarginScore(BigDecimal operatingMargin) {
        if (operatingMargin == null) {
            return 0;
        }
        if (operatingMargin.compareTo(BigDecimal.valueOf(20)) >= 0) return 20;
        if (operatingMargin.compareTo(BigDecimal.valueOf(10)) >= 0) return 15;
        if (operatingMargin.compareTo(BigDecimal.valueOf(5)) >= 0) return 10;
        if (operatingMargin.signum() >= 0) return 5;
        return 0;
    }

    /** ROA(%) 구간: ≥10→20, ≥5→15, ≥2→10, ≥0→5, 음수/null→0. */
    int roaScore(BigDecimal roa) {
        if (roa == null) {
            return 0;
        }
        if (roa.compareTo(BigDecimal.valueOf(10)) >= 0) return 20;
        if (roa.compareTo(BigDecimal.valueOf(5)) >= 0) return 15;
        if (roa.compareTo(BigDecimal.valueOf(2)) >= 0) return 10;
        if (roa.signum() >= 0) return 5;
        return 0;
    }

    /** 평판 20점 — A=20 B=15 C=10 D=5 E=0, 미상/미등록=10(중립). */
    public int reputationScore(String reputationGrade) {
        if (reputationGrade == null) {
            return 10;
        }
        return switch (reputationGrade) {
            case "A" -> 20;
            case "B" -> 15;
            case "C" -> 10;
            case "D" -> 5;
            case "E" -> 0;
            default -> 10;
        };
    }

    /** 세 축 합산 신용점수(0~100). */
    public int creditScore(CorporateFinancials fin, String reputationGrade) {
        return stabilityScore(fin.debtRatio())
                + profitabilityScore(fin.operatingMargin(), fin.roa())
                + reputationScore(reputationGrade);
    }

    /** 점수 → 등급: ≥80 A, ≥65 B, ≥50 C, ≥35 D, 그 외 E. */
    public String creditGrade(int score) {
        if (score >= 80) return "A";
        if (score >= 65) return "B";
        if (score >= 50) return "C";
        if (score >= 35) return "D";
        return "E";
    }

    /** E 등급은 대출 불가. */
    public boolean isLoanBlocked(String grade) {
        return "E".equals(grade);
    }

    // ─── 한도 · 수수료 ────────────────────────────────────────────────────────

    /** 등급별 한도 계수 — A1.0 B0.8 C0.6 D0.3 E0(불가). */
    public BigDecimal gradeFactor(String grade) {
        return switch (grade) {
            case "A" -> BigDecimal.ONE;
            case "B" -> new BigDecimal("0.8");
            case "C" -> new BigDecimal("0.6");
            case "D" -> new BigDecimal("0.3");
            default -> BigDecimal.ZERO;
        };
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
        return switch (grade) {
            case "A" -> BigDecimal.ONE;
            case "B" -> new BigDecimal("1.1");
            case "C" -> new BigDecimal("1.25");
            case "D" -> new BigDecimal("1.5");
            default -> BigDecimal.ONE;
        };
    }

    /** 수수료 = 원금 × dailyRate × termDays × gradeSurcharge. */
    public BigDecimal fee(BigDecimal principal, int termDays, String grade) {
        if (termDays < 0) {
            throw new IllegalArgumentException("대출 기간(일)은 음수일 수 없습니다: " + termDays);
        }
        return principal.multiply(dailyRate)
                .multiply(BigDecimal.valueOf(termDays))
                .multiply(gradeSurcharge(grade))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
