package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidProposalException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.util.Objects;

/**
 * 보험료 산출 계산기 — 순수 정적 도메인 서비스 ({@link BancaRuleEvaluator} 와 동일 관례).
 *
 * <p><b>보험나이</b>: 한국 보험업 표준 — 만 나이를 기준으로, 마지막 생일부터 6개월 이상
 * 경과했으면 +1 세. 요율 구간 조회의 입력이다.
 *
 * <p><b>연 보험료</b>: 보장금액 ÷ 1,000 × 요율(rate_per_mille), 원 단위 HALF_UP.
 * 요율이 없으면 폴백 없이 산출을 거부한다({@code RateNotFoundException} — 호출측 책임).
 */
public final class PremiumRater {

    /** rate_per_mille 의 분모 — 보장금액 1,000원당 요율. */
    private static final BigDecimal PER_MILLE = BigDecimal.valueOf(1000);

    /** 연 보험료 스케일 — 원 단위. */
    private static final int PREMIUM_SCALE = 0;

    private PremiumRater() {
    }

    /**
     * 보험나이 산정 — 만 나이 + (마지막 생일 후 6개월 이상 경과 시 1).
     *
     * @throws InvalidProposalException 생년월일이 기준일보다 미래인 경우
     */
    public static int insuranceAge(LocalDate birthDate, LocalDate asOf) {
        Objects.requireNonNull(birthDate, "birthDate");
        Objects.requireNonNull(asOf, "asOf");
        if (birthDate.isAfter(asOf)) {
            throw new InvalidProposalException(
                    "생년월일이 기준일보다 미래일 수 없습니다: birthDate=" + birthDate + ", asOf=" + asOf);
        }
        Period sinceBirth = Period.between(birthDate, asOf);
        return sinceBirth.getYears() + (sinceBirth.getMonths() >= 6 ? 1 : 0);
    }

    /**
     * 연 보험료 산출 = 보장금액 ÷ 1,000 × 요율, 원 단위 HALF_UP.
     *
     * @throws InvalidProposalException 보장금액·요율이 0 이하이거나, 라운딩 결과가 0원인 경우
     *                                  (0원짜리 설계는 존재할 수 없다 — 임의 최소값으로 메꾸지 않는다)
     */
    public static BigDecimal annualPremium(BigDecimal coverageAmount, BigDecimal ratePerMille) {
        Objects.requireNonNull(coverageAmount, "coverageAmount");
        Objects.requireNonNull(ratePerMille, "ratePerMille");
        if (coverageAmount.signum() <= 0) {
            throw new InvalidProposalException(
                    "보장금액은 0 보다 커야 합니다: " + coverageAmount.toPlainString());
        }
        if (ratePerMille.signum() <= 0) {
            throw new InvalidProposalException(
                    "요율은 0 보다 커야 합니다: " + ratePerMille.toPlainString());
        }
        BigDecimal premium = coverageAmount.multiply(ratePerMille)
                .divide(PER_MILLE, PREMIUM_SCALE, RoundingMode.HALF_UP);
        if (premium.signum() <= 0) {
            throw new InvalidProposalException(
                    "산출 보험료가 0원입니다 — 보장금액·요율 조합을 확인하세요: coverage="
                            + coverageAmount.toPlainString() + ", ratePerMille=" + ratePerMille.toPlainString());
        }
        return premium;
    }
}
