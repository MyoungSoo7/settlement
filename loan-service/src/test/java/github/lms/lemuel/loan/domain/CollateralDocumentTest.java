package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 담보서류 애그리거트 — (securedLoanId, fileHash) 멱등, 전이표 강제, 종결 번복은 새 첨부로만.
 */
class CollateralDocumentTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 14, 10, 0);

    private static ExtractedCollateralDocument extracted() {
        return new ExtractedCollateralDocument("홍길동", "서울시 강남구",
                new BigDecimal("500000000"), new BigDecimal("120000000"),
                LocalDate.of(2026, 8, 10), new BigDecimal("0.93"));
    }

    private static CollateralDocument newDocument() {
        return CollateralDocument.extracted(1L, 2L, 77L, "감정평가서.pdf", "application/pdf",
                "hash-abc", 4096L, extracted(), "gemini-2.5-flash", NOW);
    }

    @Test
    @DisplayName("추출 직후는 EXTRACTED — 필수값 보존")
    void createsExtracted() {
        CollateralDocument document = newDocument();

        assertThat(document.getStatus()).isEqualTo(CollateralDocumentStatus.EXTRACTED);
        assertThat(document.getSecuredLoanId()).isEqualTo(1L);
        assertThat(document.getCollateralId()).isEqualTo(2L);
        assertThat(document.getUploadedBy()).isEqualTo(77L);
        assertThat(document.getExtracted().appraisedValue()).isEqualByComparingTo("500000000");
        assertThat(document.getOcrModel()).isEqualTo("gemini-2.5-flash");
        assertThat(document.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("필수값 누락은 생성 거부")
    void rejectsMissingRequired() {
        assertThatThrownBy(() -> CollateralDocument.extracted(null, 2L, 77L, "f.pdf",
                "application/pdf", "h", 1L, extracted(), "m", NOW))
                .isInstanceOf(LoanInvariantViolationException.class);
        assertThatThrownBy(() -> CollateralDocument.extracted(1L, 2L, 0L, "f.pdf",
                "application/pdf", "h", 1L, extracted(), "m", NOW))
                .isInstanceOf(LoanInvariantViolationException.class);
        assertThatThrownBy(() -> CollateralDocument.extracted(1L, 2L, 77L, " ",
                "application/pdf", "h", 1L, extracted(), "m", NOW))
                .isInstanceOf(LoanInvariantViolationException.class);
        assertThatThrownBy(() -> CollateralDocument.extracted(1L, 2L, 77L, "f.pdf",
                "application/pdf", "h", 1L, null, "m", NOW))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    @DisplayName("대사 판정 적용 — MATCHED/MISMATCHED/NEEDS_REVIEW")
    void appliesDecision() {
        CollateralDocument matched = newDocument();
        matched.applyDecision(CollateralDocumentMatchDecision.matched(), NOW.plusSeconds(1));
        assertThat(matched.getStatus()).isEqualTo(CollateralDocumentStatus.MATCHED);
        assertThat(matched.getMatchNote()).isNull();
        assertThat(matched.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));

        CollateralDocument mismatched = newDocument();
        mismatched.applyDecision(CollateralDocumentMatchDecision.mismatched("감정평가액 불일치"), NOW);
        assertThat(mismatched.getStatus()).isEqualTo(CollateralDocumentStatus.MISMATCHED);
        assertThat(mismatched.getMatchNote()).isEqualTo("감정평가액 불일치");

        CollateralDocument review = newDocument();
        review.applyDecision(CollateralDocumentMatchDecision.needsReview("신뢰도 미달"), NOW);
        assertThat(review.getStatus()).isEqualTo(CollateralDocumentStatus.NEEDS_REVIEW);
    }

    @Test
    @DisplayName("종결 이후 재판정은 차단 — 번복은 새 서류 첨부로만")
    void terminalCannotBeReDecided() {
        CollateralDocument document = newDocument();
        document.applyDecision(CollateralDocumentMatchDecision.matched(), NOW);

        assertThatThrownBy(() -> document.applyDecision(
                CollateralDocumentMatchDecision.mismatched("x"), NOW))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    @DisplayName("운영자 리뷰 — NEEDS_REVIEW 에서만 확정/반려, 리뷰어 필수")
    void operatorReview() {
        CollateralDocument document = newDocument();
        document.applyDecision(CollateralDocumentMatchDecision.needsReview("선순위 판독 불가"), NOW);

        document.reviewMatch(99L, "등기부 육안 대조 완료", NOW.plusMinutes(5));

        assertThat(document.getStatus()).isEqualTo(CollateralDocumentStatus.MATCHED);
        assertThat(document.getReviewedBy()).isEqualTo(99L);
        assertThat(document.getMatchNote()).isEqualTo("등기부 육안 대조 완료");
    }

    @Test
    @DisplayName("EXTRACTED 상태에서 리뷰는 불가 — 자동 대사가 먼저다, 리뷰어 누락도 거부")
    void reviewGuards() {
        CollateralDocument document = newDocument();
        assertThatThrownBy(() -> document.reviewMatch(99L, "note", NOW))
                .isInstanceOf(LoanInvariantViolationException.class);

        CollateralDocument needsReview = newDocument();
        needsReview.applyDecision(CollateralDocumentMatchDecision.needsReview("신뢰도 미달"), NOW);
        assertThatThrownBy(() -> needsReview.reviewMatch(null, "note", NOW))
                .isInstanceOf(LoanInvariantViolationException.class);
    }
}
