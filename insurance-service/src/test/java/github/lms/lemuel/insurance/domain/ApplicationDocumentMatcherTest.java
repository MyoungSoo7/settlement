package github.lms.lemuel.insurance.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 청약 ↔ 청약서류 대사 규칙 (순수 도메인).
 *
 * <p>판정 순서: 신뢰도 → 연 보험료(정확 일치) → 보장금액(정확 일치, null 은 리뷰) →
 * 청약일(접수일 KST ±1일, null 은 리뷰) → MATCHED. 성명·상품명은 판정 불사용.
 */
class ApplicationDocumentMatcherTest {

    private static final BigDecimal THRESHOLD = new BigDecimal("0.80");
    /** 접수 시각: 2026-08-10 14:00 KST (05:00 UTC) */
    private static final Instant SUBMITTED_AT = Instant.parse("2026-08-10T05:00:00Z");
    private static final BigDecimal PREMIUM = new BigDecimal("1200000");
    private static final BigDecimal COVERAGE = new BigDecimal("100000000");

    private static ExtractedApplicationForm form(String premium, String coverage,
                                                 LocalDate date, String confidence) {
        return new ExtractedApplicationForm("김계약", "이피보", "종신보험A", date,
                new BigDecimal(premium), coverage == null ? null : new BigDecimal(coverage),
                new BigDecimal(confidence));
    }

    @Test
    @DisplayName("보험료·보장금액·청약일 일치 + 신뢰도 충족이면 MATCHED")
    void matched() {
        DocumentMatchDecision decision = ApplicationDocumentMatcher.decide(
                form("1200000", "100000000", LocalDate.of(2026, 8, 10), "0.93"),
                PREMIUM, COVERAGE, SUBMITTED_AT, THRESHOLD);

        assertThat(decision.status()).isEqualTo(ApplicationDocumentStatus.MATCHED);
        assertThat(decision.note()).isNull();
    }

    @Test
    @DisplayName("신뢰도 미달이면 값이 일치해도 NEEDS_REVIEW — 임계 동률(0.80)은 통과")
    void confidenceThreshold() {
        assertThat(ApplicationDocumentMatcher.decide(
                form("1200000", "100000000", LocalDate.of(2026, 8, 10), "0.79"),
                PREMIUM, COVERAGE, SUBMITTED_AT, THRESHOLD).status())
                .isEqualTo(ApplicationDocumentStatus.NEEDS_REVIEW);
        assertThat(ApplicationDocumentMatcher.decide(
                form("1200000", "100000000", LocalDate.of(2026, 8, 10), "0.80"),
                PREMIUM, COVERAGE, SUBMITTED_AT, THRESHOLD).status())
                .isEqualTo(ApplicationDocumentStatus.MATCHED);
    }

    @Test
    @DisplayName("연 보험료 불일치는 MISMATCHED — 1원 차이도 불일치, scale 차이는 일치")
    void premiumExactMatch() {
        DocumentMatchDecision mismatch = ApplicationDocumentMatcher.decide(
                form("1200001", "100000000", LocalDate.of(2026, 8, 10), "0.93"),
                PREMIUM, COVERAGE, SUBMITTED_AT, THRESHOLD);
        assertThat(mismatch.status()).isEqualTo(ApplicationDocumentStatus.MISMATCHED);
        assertThat(mismatch.note()).contains("보험료");

        assertThat(ApplicationDocumentMatcher.decide(
                form("1200000.00", "100000000", LocalDate.of(2026, 8, 10), "0.93"),
                PREMIUM, COVERAGE, SUBMITTED_AT, THRESHOLD).status())
                .isEqualTo(ApplicationDocumentStatus.MATCHED);
    }

    @Test
    @DisplayName("보장금액 불일치는 MISMATCHED, 판독 불가(null)는 NEEDS_REVIEW")
    void coverageRules() {
        DocumentMatchDecision mismatch = ApplicationDocumentMatcher.decide(
                form("1200000", "90000000", LocalDate.of(2026, 8, 10), "0.93"),
                PREMIUM, COVERAGE, SUBMITTED_AT, THRESHOLD);
        assertThat(mismatch.status()).isEqualTo(ApplicationDocumentStatus.MISMATCHED);
        assertThat(mismatch.note()).contains("보장금액");

        DocumentMatchDecision review = ApplicationDocumentMatcher.decide(
                form("1200000", null, LocalDate.of(2026, 8, 10), "0.93"),
                PREMIUM, COVERAGE, SUBMITTED_AT, THRESHOLD);
        assertThat(review.status()).isEqualTo(ApplicationDocumentStatus.NEEDS_REVIEW);
        assertThat(review.note()).contains("보장금액");
    }

    @Test
    @DisplayName("청약일은 접수일(KST) ±1일 허용 — 2일 차이는 MISMATCHED, 판독 불가는 리뷰")
    void dateRules() {
        assertThat(ApplicationDocumentMatcher.decide(
                form("1200000", "100000000", LocalDate.of(2026, 8, 9), "0.93"),
                PREMIUM, COVERAGE, SUBMITTED_AT, THRESHOLD).status())
                .isEqualTo(ApplicationDocumentStatus.MATCHED);

        DocumentMatchDecision twoDays = ApplicationDocumentMatcher.decide(
                form("1200000", "100000000", LocalDate.of(2026, 8, 8), "0.93"),
                PREMIUM, COVERAGE, SUBMITTED_AT, THRESHOLD);
        assertThat(twoDays.status()).isEqualTo(ApplicationDocumentStatus.MISMATCHED);
        assertThat(twoDays.note()).contains("청약일");

        assertThat(ApplicationDocumentMatcher.decide(
                form("1200000", "100000000", null, "0.93"),
                PREMIUM, COVERAGE, SUBMITTED_AT, THRESHOLD).status())
                .isEqualTo(ApplicationDocumentStatus.NEEDS_REVIEW);
    }

    @Test
    @DisplayName("접수일 판정은 KST 기준 — UTC 15:30(=KST 익일 00:30) 접수와 익일+1 서류는 같은 허용폭")
    void submittedDateIsKst() {
        // 2026-08-10T15:30Z = 2026-08-11 00:30 KST → 접수일은 8/11, 8/12 서류는 1일 차로 허용
        Instant lateNightUtc = Instant.parse("2026-08-10T15:30:00Z");

        assertThat(ApplicationDocumentMatcher.decide(
                form("1200000", "100000000", LocalDate.of(2026, 8, 12), "0.93"),
                PREMIUM, COVERAGE, lateNightUtc, THRESHOLD).status())
                .isEqualTo(ApplicationDocumentStatus.MATCHED);
    }

    @Test
    @DisplayName("신뢰도 미달이 보험료 불일치보다 먼저다 — 믿을 수 없는 값으로 불일치를 선고하지 않는다")
    void confidencePrecedesPremium() {
        assertThat(ApplicationDocumentMatcher.decide(
                form("9999999", "100000000", LocalDate.of(2026, 8, 10), "0.30"),
                PREMIUM, COVERAGE, SUBMITTED_AT, THRESHOLD).status())
                .isEqualTo(ApplicationDocumentStatus.NEEDS_REVIEW);
    }
}
