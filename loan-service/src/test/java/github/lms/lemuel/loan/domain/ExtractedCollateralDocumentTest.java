package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 담보서류 OCR 추출 VO 불변식 — 감정평가액·신뢰도 필수, 나머지는 판독 실패를 null 로 표현한다.
 */
class ExtractedCollateralDocumentTest {

    @Test
    @DisplayName("정상 추출 결과를 보관한다")
    void createsValid() {
        ExtractedCollateralDocument doc = new ExtractedCollateralDocument(
                "홍길동", "서울시 강남구 역삼동 123-4", new BigDecimal("500000000"),
                new BigDecimal("120000000"), LocalDate.of(2026, 8, 10), new BigDecimal("0.93"));

        assertThat(doc.ownerName()).isEqualTo("홍길동");
        assertThat(doc.locationText()).isEqualTo("서울시 강남구 역삼동 123-4");
        assertThat(doc.appraisedValue()).isEqualByComparingTo("500000000");
        assertThat(doc.seniorClaimAmount()).isEqualByComparingTo("120000000");
        assertThat(doc.appraisalDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(doc.confidence()).isEqualByComparingTo("0.93");
    }

    @Test
    @DisplayName("소유자·소재지·선순위·평가기준일은 판독 실패(null) 허용 — 공백은 null 정규화")
    void optionalFieldsMayBeNull() {
        ExtractedCollateralDocument doc = new ExtractedCollateralDocument(
                "  ", null, new BigDecimal("500000000"), null, null, new BigDecimal("0.50"));

        assertThat(doc.ownerName()).isNull();
        assertThat(doc.locationText()).isNull();
        assertThat(doc.seniorClaimAmount()).isNull();
        assertThat(doc.appraisalDate()).isNull();
    }

    @Test
    @DisplayName("감정평가액은 필수·양수, 선순위는 존재 시 0 이상")
    void amountInvariants() {
        assertThatThrownBy(() -> new ExtractedCollateralDocument(null, null,
                null, null, null, new BigDecimal("0.9")))
                .isInstanceOf(LoanInvariantViolationException.class);
        assertThatThrownBy(() -> new ExtractedCollateralDocument(null, null,
                BigDecimal.ZERO, null, null, new BigDecimal("0.9")))
                .isInstanceOf(LoanInvariantViolationException.class);
        assertThatThrownBy(() -> new ExtractedCollateralDocument(null, null,
                new BigDecimal("100"), new BigDecimal("-1"), null, new BigDecimal("0.9")))
                .isInstanceOf(LoanInvariantViolationException.class);
        // 선순위 0 은 허용 — 무담보 설정(선순위 없음)의 명시 표기
        assertThat(new ExtractedCollateralDocument(null, null,
                new BigDecimal("100"), BigDecimal.ZERO, null, new BigDecimal("0.9"))
                .seniorClaimAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("신뢰도는 0~1 범위 필수 — 경계값 0·1 은 허용")
    void confidenceRange() {
        assertThatThrownBy(() -> new ExtractedCollateralDocument(null, null,
                new BigDecimal("100"), null, null, null))
                .isInstanceOf(LoanInvariantViolationException.class);
        assertThatThrownBy(() -> new ExtractedCollateralDocument(null, null,
                new BigDecimal("100"), null, null, new BigDecimal("1.01")))
                .isInstanceOf(LoanInvariantViolationException.class);
        assertThat(new ExtractedCollateralDocument(null, null,
                new BigDecimal("100"), null, null, BigDecimal.ZERO).confidence()).isEqualByComparingTo("0");
        assertThat(new ExtractedCollateralDocument(null, null,
                new BigDecimal("100"), null, null, BigDecimal.ONE).confidence()).isEqualByComparingTo("1");
    }
}
