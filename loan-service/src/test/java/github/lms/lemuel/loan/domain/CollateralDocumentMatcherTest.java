package github.lms.lemuel.loan.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 담보 설정값 ↔ 담보서류 대사 규칙 (순수 도메인).
 *
 * <p>판정 순서: 신뢰도 → 감정평가액(정확 일치) → 선순위 채권최고액(신고값 검증) →
 * 평가기준일(±1일, null 은 리뷰) → MATCHED. 소유자·소재지는 판정 불사용.
 */
class CollateralDocumentMatcherTest {

    private static final BigDecimal THRESHOLD = new BigDecimal("0.80");
    private static final LocalDateTime APPRAISED_AT = LocalDateTime.of(2026, 8, 10, 14, 0);
    private static final BigDecimal APPRAISED = new BigDecimal("500000000.00");
    private static final BigDecimal SENIOR = new BigDecimal("120000000.00");

    private static ExtractedCollateralDocument doc(String appraised, String senior,
                                                   LocalDate date, String confidence) {
        return new ExtractedCollateralDocument("홍길동", "서울시 강남구", new BigDecimal(appraised),
                senior == null ? null : new BigDecimal(senior), date, new BigDecimal(confidence));
    }

    @Test
    @DisplayName("평가액·선순위·평가기준일 일치 + 신뢰도 충족이면 MATCHED — scale 차이는 일치")
    void matched() {
        CollateralDocumentMatchDecision decision = CollateralDocumentMatcher.decide(
                doc("500000000", "120000000", LocalDate.of(2026, 8, 10), "0.93"),
                APPRAISED, SENIOR, APPRAISED_AT, THRESHOLD);

        assertThat(decision.status()).isEqualTo(CollateralDocumentStatus.MATCHED);
        assertThat(decision.note()).isNull();
    }

    @Test
    @DisplayName("신뢰도 미달이면 값이 일치해도 NEEDS_REVIEW — 임계 동률(0.80)은 통과")
    void confidenceThreshold() {
        assertThat(CollateralDocumentMatcher.decide(
                doc("500000000", "120000000", LocalDate.of(2026, 8, 10), "0.79"),
                APPRAISED, SENIOR, APPRAISED_AT, THRESHOLD).status())
                .isEqualTo(CollateralDocumentStatus.NEEDS_REVIEW);
        assertThat(CollateralDocumentMatcher.decide(
                doc("500000000", "120000000", LocalDate.of(2026, 8, 10), "0.80"),
                APPRAISED, SENIOR, APPRAISED_AT, THRESHOLD).status())
                .isEqualTo(CollateralDocumentStatus.MATCHED);
    }

    @Test
    @DisplayName("감정평가액 불일치는 MISMATCHED — 1원 차이도 불일치 (한도 산정의 원천)")
    void appraisedValueExactMatch() {
        CollateralDocumentMatchDecision decision = CollateralDocumentMatcher.decide(
                doc("500000001", "120000000", LocalDate.of(2026, 8, 10), "0.93"),
                APPRAISED, SENIOR, APPRAISED_AT, THRESHOLD);

        assertThat(decision.status()).isEqualTo(CollateralDocumentStatus.MISMATCHED);
        assertThat(decision.note()).contains("감정평가액");
    }

    @Test
    @DisplayName("선순위 불일치는 MISMATCHED — 자기신고값의 유일한 검증 수단")
    void seniorClaimMismatch() {
        CollateralDocumentMatchDecision decision = CollateralDocumentMatcher.decide(
                doc("500000000", "80000000", LocalDate.of(2026, 8, 10), "0.93"),
                APPRAISED, SENIOR, APPRAISED_AT, THRESHOLD);

        assertThat(decision.status()).isEqualTo(CollateralDocumentStatus.MISMATCHED);
        assertThat(decision.note()).contains("선순위");
    }

    @Test
    @DisplayName("선순위 판독 불가(null): 신고값 0 이면 통과, 0 이 아니면 NEEDS_REVIEW")
    void seniorClaimUnreadable() {
        // 신고 선순위가 있는데 서류에서 못 읽음 → 육안 확인
        CollateralDocumentMatchDecision review = CollateralDocumentMatcher.decide(
                doc("500000000", null, LocalDate.of(2026, 8, 10), "0.93"),
                APPRAISED, SENIOR, APPRAISED_AT, THRESHOLD);
        assertThat(review.status()).isEqualTo(CollateralDocumentStatus.NEEDS_REVIEW);
        assertThat(review.note()).contains("선순위");

        // 신고 선순위 0 — 확인할 대상이 없어 통과
        CollateralDocumentMatchDecision pass = CollateralDocumentMatcher.decide(
                doc("500000000", null, LocalDate.of(2026, 8, 10), "0.93"),
                APPRAISED, BigDecimal.ZERO, APPRAISED_AT, THRESHOLD);
        assertThat(pass.status()).isEqualTo(CollateralDocumentStatus.MATCHED);
    }

    @Test
    @DisplayName("평가기준일은 설정 시각 ±1일 허용 — 2일 차이는 MISMATCHED, 판독 불가는 리뷰")
    void dateRules() {
        assertThat(CollateralDocumentMatcher.decide(
                doc("500000000", "120000000", LocalDate.of(2026, 8, 9), "0.93"),
                APPRAISED, SENIOR, APPRAISED_AT, THRESHOLD).status())
                .isEqualTo(CollateralDocumentStatus.MATCHED);

        CollateralDocumentMatchDecision twoDays = CollateralDocumentMatcher.decide(
                doc("500000000", "120000000", LocalDate.of(2026, 8, 8), "0.93"),
                APPRAISED, SENIOR, APPRAISED_AT, THRESHOLD);
        assertThat(twoDays.status()).isEqualTo(CollateralDocumentStatus.MISMATCHED);
        assertThat(twoDays.note()).contains("평가기준일");

        assertThat(CollateralDocumentMatcher.decide(
                doc("500000000", "120000000", null, "0.93"),
                APPRAISED, SENIOR, APPRAISED_AT, THRESHOLD).status())
                .isEqualTo(CollateralDocumentStatus.NEEDS_REVIEW);
    }

    @Test
    @DisplayName("신뢰도 미달이 평가액 불일치보다 먼저다 — 믿을 수 없는 값으로 불일치를 선고하지 않는다")
    void confidencePrecedesAmount() {
        assertThat(CollateralDocumentMatcher.decide(
                doc("999999999", "120000000", LocalDate.of(2026, 8, 10), "0.30"),
                APPRAISED, SENIOR, APPRAISED_AT, THRESHOLD).status())
                .isEqualTo(CollateralDocumentStatus.NEEDS_REVIEW);
    }
}
