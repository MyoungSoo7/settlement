package github.lms.lemuel.tax.domain.scan;

import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OCR 추출 필드 VO — <b>구조는 강제(throw)하고 내용은 판정(flag)한다</b>.
 *
 * <p>음수 금액·필수값 누락 같은 <i>구조적</i> 파손은 예외로 막지만, 세액이 공급가액의 10%와 어긋나는 것은
 * 스캔본이 실제로 그렇게 생겼을 수 있으므로(또는 OCR 이 잘못 읽었으므로) 예외가 아니라 리뷰 사유다.
 *
 * <p>신뢰도도 {@link BigDecimal} 이다 — 금액 스코프에서 double/float 은 금지(MONEY-PRIMITIVE 가드)이고,
 * 임계값 비교를 부동소수 오차 없이 하려는 의도도 겸한다.
 */
class ExtractedTaxInvoiceTest {

    private static final LocalDate WRITTEN = LocalDate.of(2026, 8, 1);
    private static final BigDecimal THRESHOLD = new BigDecimal("0.80");

    private static ExtractedTaxInvoice of(String supply, String tax, String total, String confidence) {
        return ExtractedTaxInvoice.of("101-81-00001", "101-81-00001", WRITTEN,
                new BigDecimal(supply), new BigDecimal(tax), new BigDecimal(total),
                "TI-0000000005", new BigDecimal(confidence));
    }

    @Test
    @DisplayName("정합한 계산서 — 합계·부가세 모두 일치하면 리뷰 불필요")
    void consistent() {
        ExtractedTaxInvoice extracted = of("100000", "10000", "110000", "0.97");

        assertThat(extracted.totalConsistent()).isTrue();
        assertThat(extracted.vatConsistent()).isTrue();
        assertThat(extracted.needsReview(THRESHOLD)).isFalse();
        assertThat(extracted.supplyAmount()).isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("scale 이 달라도 금액 비교는 값으로 한다 (BigDecimal equals 함정)")
    void scaleInsensitiveComparison() {
        ExtractedTaxInvoice extracted = of("100000.00", "10000.0", "110000", "0.99");

        assertThat(extracted.totalConsistent()).isTrue();
        assertThat(extracted.vatConsistent()).isTrue();
    }

    @Test
    @DisplayName("합계가 공급가액+세액과 다르면 리뷰 대상 (예외는 아니다)")
    void totalMismatchIsReviewNotThrow() {
        ExtractedTaxInvoice extracted = of("100000", "10000", "109000", "0.99");

        assertThat(extracted.totalConsistent()).isFalse();
        assertThat(extracted.needsReview(THRESHOLD)).isTrue();
    }

    @Test
    @DisplayName("세액이 공급가액의 10%(원단위 절사)와 다르면 리뷰 대상")
    void vatMismatchIsReview() {
        ExtractedTaxInvoice extracted = of("100000", "9000", "109000", "0.99");

        assertThat(extracted.vatConsistent()).isFalse();
        assertThat(extracted.needsReview(THRESHOLD)).isTrue();
    }

    @Test
    @DisplayName("부가세 기대값은 원단위 절사 — 1원 미만은 버린다")
    void expectedTaxFloorsToWon() {
        // 12,345 × 10% = 1,234.5 → 절사 1,234
        ExtractedTaxInvoice extracted = of("12345", "1234", "13579", "0.99");

        assertThat(extracted.expectedTaxAmount()).isEqualByComparingTo("1234");
        assertThat(extracted.vatConsistent()).isTrue();
        assertThat(extracted.totalConsistent()).isTrue();
    }

    @Test
    @DisplayName("0원 계산서도 구조적으로 유효하다 (경계값)")
    void zeroAmounts() {
        ExtractedTaxInvoice extracted = of("0", "0", "0", "0.95");

        assertThat(extracted.totalConsistent()).isTrue();
        assertThat(extracted.vatConsistent()).isTrue();
        assertThat(extracted.needsReview(THRESHOLD)).isFalse();
    }

    @Test
    @DisplayName("신뢰도가 임계값 미만이면 리뷰 대상 — 경계값은 통과(>= 임계)")
    void lowConfidenceNeedsReview() {
        assertThat(of("100000", "10000", "110000", "0.55").needsReview(THRESHOLD)).isTrue();
        assertThat(of("100000", "10000", "110000", "0.80").needsReview(THRESHOLD)).isFalse();
        assertThat(of("100000", "10000", "110000", "0.7999").needsReview(THRESHOLD)).isTrue();
    }

    @Test
    @DisplayName("공급자 사업자번호 체크섬이 깨졌으면 금액이 맞아도 리뷰 대상")
    void invalidSupplierNeedsReview() {
        ExtractedTaxInvoice extracted = ExtractedTaxInvoice.of("101-81-00002", null, WRITTEN,
                new BigDecimal("100000"), new BigDecimal("10000"), new BigDecimal("110000"),
                null, new BigDecimal("0.99"));

        assertThat(extracted.supplier().isValid()).isFalse();
        assertThat(extracted.needsReview(THRESHOLD)).isTrue();
    }

    @Test
    @DisplayName("음수 금액은 구조 파손 — 예외")
    void negativeAmountThrows() {
        assertThatThrownBy(() -> of("-1", "0", "-1", "0.9"))
                .isInstanceOf(TaxInvariantViolationException.class)
                .hasMessageContaining("음수");
    }

    @Test
    @DisplayName("금액 누락·작성일자 누락은 예외")
    void missingRequiredThrows() {
        assertThatThrownBy(() -> ExtractedTaxInvoice.of("101-81-00001", null, WRITTEN,
                null, BigDecimal.ZERO, BigDecimal.ZERO, null, new BigDecimal("0.9")))
                .isInstanceOf(TaxInvariantViolationException.class)
                .hasMessageContaining("공급가액");

        assertThatThrownBy(() -> ExtractedTaxInvoice.of("101-81-00001", null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, new BigDecimal("0.9")))
                .isInstanceOf(TaxInvariantViolationException.class)
                .hasMessageContaining("작성일자");
    }

    @Test
    @DisplayName("신뢰도는 0~1 범위 밖이거나 누락이면 예외")
    void confidenceOutOfRangeThrows() {
        assertThatThrownBy(() -> of("100000", "10000", "110000", "1.2"))
                .isInstanceOf(TaxInvariantViolationException.class)
                .hasMessageContaining("신뢰도");
        assertThatThrownBy(() -> of("100000", "10000", "110000", "-0.1"))
                .isInstanceOf(TaxInvariantViolationException.class)
                .hasMessageContaining("신뢰도");
        assertThatThrownBy(() -> ExtractedTaxInvoice.of("101-81-00001", null, WRITTEN,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null))
                .isInstanceOf(TaxInvariantViolationException.class)
                .hasMessageContaining("신뢰도");
    }
}
