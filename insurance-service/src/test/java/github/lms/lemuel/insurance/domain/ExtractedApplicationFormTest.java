package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidApplicationDocumentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 청약서 OCR 추출 VO 불변식 — 연 보험료·신뢰도 필수, 나머지는 판독 실패를 null 로 표현한다.
 */
class ExtractedApplicationFormTest {

    @Test
    @DisplayName("정상 추출 결과를 보관한다")
    void createsValid() {
        ExtractedApplicationForm form = new ExtractedApplicationForm(
                "김계약", "이피보", "종신보험A", LocalDate.of(2026, 8, 10),
                new BigDecimal("1200000"), new BigDecimal("100000000"), new BigDecimal("0.93"));

        assertThat(form.contractorName()).isEqualTo("김계약");
        assertThat(form.insuredName()).isEqualTo("이피보");
        assertThat(form.productName()).isEqualTo("종신보험A");
        assertThat(form.applicationDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(form.annualPremium()).isEqualByComparingTo("1200000");
        assertThat(form.coverageAmount()).isEqualByComparingTo("100000000");
        assertThat(form.confidence()).isEqualByComparingTo("0.93");
    }

    @Test
    @DisplayName("성명·상품명·청약일·보장금액은 판독 실패(null) 허용 — 공백은 null 정규화")
    void optionalFieldsMayBeNull() {
        ExtractedApplicationForm form = new ExtractedApplicationForm(
                "  ", null, "", null, new BigDecimal("1200000"), null, new BigDecimal("0.50"));

        assertThat(form.contractorName()).isNull();
        assertThat(form.insuredName()).isNull();
        assertThat(form.productName()).isNull();
        assertThat(form.applicationDate()).isNull();
        assertThat(form.coverageAmount()).isNull();
    }

    @Test
    @DisplayName("연 보험료는 필수·양수 — 0원·음수·누락은 거부")
    void premiumMustBePositive() {
        assertThatThrownBy(() -> new ExtractedApplicationForm(null, null, null, null,
                null, null, new BigDecimal("0.9")))
                .isInstanceOf(InvalidApplicationDocumentException.class);
        assertThatThrownBy(() -> new ExtractedApplicationForm(null, null, null, null,
                BigDecimal.ZERO, null, new BigDecimal("0.9")))
                .isInstanceOf(InvalidApplicationDocumentException.class);
        assertThatThrownBy(() -> new ExtractedApplicationForm(null, null, null, null,
                new BigDecimal("-1"), null, new BigDecimal("0.9")))
                .isInstanceOf(InvalidApplicationDocumentException.class);
    }

    @Test
    @DisplayName("보장금액이 존재하면 양수여야 한다")
    void coverageWhenPresentMustBePositive() {
        assertThatThrownBy(() -> new ExtractedApplicationForm(null, null, null, null,
                new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("0.9")))
                .isInstanceOf(InvalidApplicationDocumentException.class);
    }

    @Test
    @DisplayName("신뢰도는 0~1 범위 필수 — 경계값 0·1 은 허용")
    void confidenceRange() {
        assertThatThrownBy(() -> new ExtractedApplicationForm(null, null, null, null,
                new BigDecimal("1000"), null, null))
                .isInstanceOf(InvalidApplicationDocumentException.class);
        assertThatThrownBy(() -> new ExtractedApplicationForm(null, null, null, null,
                new BigDecimal("1000"), null, new BigDecimal("1.01")))
                .isInstanceOf(InvalidApplicationDocumentException.class);
        assertThat(new ExtractedApplicationForm(null, null, null, null,
                new BigDecimal("1000"), null, BigDecimal.ZERO).confidence()).isEqualByComparingTo("0");
        assertThat(new ExtractedApplicationForm(null, null, null, null,
                new BigDecimal("1000"), null, BigDecimal.ONE).confidence()).isEqualByComparingTo("1");
    }
}
