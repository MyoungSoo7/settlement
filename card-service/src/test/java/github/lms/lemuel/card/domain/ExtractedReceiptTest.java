package github.lms.lemuel.card.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OCR 추출 결과 VO 불변식 — 총액·총액신뢰도는 필수, 상호명·거래일은 판독 실패를 null 로 표현한다
 * (지어내지 않는다 — ADR 0036 무폴백 원칙).
 *
 * <p>신뢰도는 <b>필드마다</b> 갖는다. 하나로 합쳐 두면 쉬운 필드의 확신이 어려운 필드의 불확실성을
 * 덮어 멀쩡한 영수증이 종결된다(회귀 케이스는 {@link ExpenseReceiptMatcherTest} 에 있다).
 */
class ExtractedReceiptTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 10);

    @Test
    @DisplayName("정상 추출 결과를 보관한다 — 신뢰도는 필드마다 따로")
    void createsValid() {
        ExtractedReceipt extracted = new ExtractedReceipt(
                "김밥천국 강남점", DATE, new BigDecimal("12000"),
                new BigDecimal("0.93"), new BigDecimal("0.41"));

        assertThat(extracted.merchantName()).isEqualTo("김밥천국 강남점");
        assertThat(extracted.transactionDate()).isEqualTo(DATE);
        assertThat(extracted.totalAmount()).isEqualByComparingTo("12000");
        assertThat(extracted.amountConfidence()).isEqualByComparingTo("0.93");
        assertThat(extracted.dateConfidence()).isEqualByComparingTo("0.41");
    }

    @Test
    @DisplayName("상호명·거래일은 판독 실패(null) 허용 — 공백 상호명은 null 로 정규화")
    void optionalFieldsMayBeNull() {
        ExtractedReceipt extracted = new ExtractedReceipt(
                "   ", null, new BigDecimal("12000"), new BigDecimal("0.50"), null);

        assertThat(extracted.merchantName()).isNull();
        assertThat(extracted.transactionDate()).isNull();
        assertThat(extracted.dateConfidence()).isNull();
    }

    @Test
    @DisplayName("총액은 필수·양수 — 0원·음수·누락은 거부")
    void totalAmountMustBePositive() {
        assertThatThrownBy(() -> new ExtractedReceipt("가게", DATE, null,
                new BigDecimal("0.9"), new BigDecimal("0.9")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExtractedReceipt("가게", DATE, BigDecimal.ZERO,
                new BigDecimal("0.9"), new BigDecimal("0.9")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExtractedReceipt("가게", DATE, new BigDecimal("-100"),
                new BigDecimal("0.9"), new BigDecimal("0.9")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("총액 신뢰도는 0~1 범위 필수")
    void amountConfidenceMustBeInRange() {
        assertThatThrownBy(() -> new ExtractedReceipt("가게", DATE, new BigDecimal("100"),
                null, new BigDecimal("0.9")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExtractedReceipt("가게", DATE, new BigDecimal("100"),
                new BigDecimal("-0.1"), new BigDecimal("0.9")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExtractedReceipt("가게", DATE, new BigDecimal("100"),
                new BigDecimal("1.01"), new BigDecimal("0.9")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("거래일 신뢰도도 0~1 범위 — 거래일이 있으면 필수")
    void dateConfidenceMustBeInRangeWhenDatePresent() {
        assertThatThrownBy(() -> new ExtractedReceipt("가게", DATE, new BigDecimal("100"),
                new BigDecimal("0.9"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("거래일");
        assertThatThrownBy(() -> new ExtractedReceipt("가게", DATE, new BigDecimal("100"),
                new BigDecimal("0.9"), new BigDecimal("1.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("거래일을 못 읽었으면 거래일 신뢰도도 없어야 한다 — 없는 필드에 숫자를 붙이지 않는다")
    void dateConfidenceMustBeAbsentWhenDateAbsent() {
        assertThatThrownBy(() -> new ExtractedReceipt("가게", null, new BigDecimal("100"),
                new BigDecimal("0.9"), new BigDecimal("0.9")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("경계값: 신뢰도 0 과 1 은 허용")
    void confidenceBoundaries() {
        assertThat(new ExtractedReceipt("가게", null, new BigDecimal("1"), BigDecimal.ZERO, null)
                .amountConfidence()).isEqualByComparingTo("0");
        assertThat(new ExtractedReceipt("가게", DATE, new BigDecimal("1"), BigDecimal.ONE, BigDecimal.ONE)
                .dateConfidence()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("weakestConfidence 는 가장 못 믿는 필드를 돌려준다 — 표시 전용")
    void weakestConfidencePicksTheLeastTrusted() {
        assertThat(new ExtractedReceipt("가게", DATE, new BigDecimal("100"),
                new BigDecimal("0.98"), new BigDecimal("0.30")).weakestConfidence())
                .isEqualByComparingTo("0.30");
        assertThat(new ExtractedReceipt("가게", DATE, new BigDecimal("100"),
                new BigDecimal("0.30"), new BigDecimal("0.98")).weakestConfidence())
                .isEqualByComparingTo("0.30");
    }

    @Test
    @DisplayName("거래일이 없으면 weakestConfidence 는 총액 신뢰도다")
    void weakestConfidenceFallsBackToAmountWhenNoDate() {
        assertThat(new ExtractedReceipt("가게", null, new BigDecimal("100"),
                new BigDecimal("0.77"), null).weakestConfidence())
                .isEqualByComparingTo("0.77");
    }
}
