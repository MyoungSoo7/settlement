package github.lms.lemuel.tax.domain;

import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;

import java.util.Locale;

/**
 * 셀러 세무유형 — 원천징수 대상 판단의 정본 입력.
 *
 * <ul>
 *   <li>{@link #INDIVIDUAL} 개인(비사업자): 사업소득 3.3% 원천징수 대상.</li>
 *   <li>{@link #BUSINESS} 사업자·법인: 원천징수 미대상(0).</li>
 * </ul>
 *
 * <p>부가세(수수료 10%)는 세무유형과 무관하게 발생한다 — 유형은 원천징수 여부만 가른다.
 */
public enum TaxType {
    INDIVIDUAL,
    BUSINESS;

    /** 사업소득 원천징수 대상 여부(개인만 대상). */
    public boolean isWithholdingApplicable() {
        return this == INDIVIDUAL;
    }

    public static TaxType fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new TaxInvariantViolationException("taxType 은 필수입니다");
        }
        try {
            return TaxType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new TaxInvariantViolationException("알 수 없는 taxType: " + raw);
        }
    }
}
