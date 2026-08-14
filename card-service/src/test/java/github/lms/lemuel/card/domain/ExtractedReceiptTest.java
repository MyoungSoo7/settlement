package github.lms.lemuel.card.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OCR 추출 결과 VO 불변식 — 총액·신뢰도는 필수, 상호명·거래일은 판독 실패를 null 로 표현한다
 * (지어내지 않는다 — ADR 0036 무폴백 원칙).
 */
class ExtractedReceiptTest {

    @Test
    @DisplayName("정상 추출 결과를 보관한다")
    void createsValid() {
        ExtractedReceipt extracted = new ExtractedReceipt(
                "김밥천국 강남점", LocalDate.of(2026, 8, 10), new BigDecimal("12000"), new BigDecimal("0.93"));

        assertThat(extracted.merchantName()).isEqualTo("김밥천국 강남점");
        assertThat(extracted.transactionDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(extracted.totalAmount()).isEqualByComparingTo("12000");
        assertThat(extracted.confidence()).isEqualByComparingTo("0.93");
    }

    @Test
    @DisplayName("상호명·거래일은 판독 실패(null) 허용 — 공백 상호명은 null 로 정규화")
    void optionalFieldsMayBeNull() {
        ExtractedReceipt extracted = new ExtractedReceipt(
                "   ", null, new BigDecimal("12000"), new BigDecimal("0.50"));

        assertThat(extracted.merchantName()).isNull();
        assertThat(extracted.transactionDate()).isNull();
    }

    @Test
    @DisplayName("총액은 필수·양수 — 0원·음수·누락은 거부")
    void totalAmountMustBePositive() {
        assertThatThrownBy(() -> new ExtractedReceipt("가게", LocalDate.now(), null, new BigDecimal("0.9")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExtractedReceipt("가게", LocalDate.now(), BigDecimal.ZERO, new BigDecimal("0.9")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExtractedReceipt("가게", LocalDate.now(), new BigDecimal("-100"), new BigDecimal("0.9")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("신뢰도는 0~1 범위 필수")
    void confidenceMustBeInRange() {
        assertThatThrownBy(() -> new ExtractedReceipt("가게", LocalDate.now(), new BigDecimal("100"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExtractedReceipt("가게", LocalDate.now(), new BigDecimal("100"), new BigDecimal("-0.1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExtractedReceipt("가게", LocalDate.now(), new BigDecimal("100"), new BigDecimal("1.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("경계값: 신뢰도 0 과 1 은 허용")
    void confidenceBoundaries() {
        assertThat(new ExtractedReceipt("가게", null, new BigDecimal("1"), BigDecimal.ZERO).confidence())
                .isEqualByComparingTo("0");
        assertThat(new ExtractedReceipt("가게", null, new BigDecimal("1"), BigDecimal.ONE).confidence())
                .isEqualByComparingTo("1");
    }
}
