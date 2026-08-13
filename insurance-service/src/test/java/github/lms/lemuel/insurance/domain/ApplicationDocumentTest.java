package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidApplicationDocumentException;
import github.lms.lemuel.insurance.domain.exception.InvalidApplicationDocumentTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 청약서류 애그리거트 — (applicationId, fileHash) 멱등, 전이표 강제, 종결 번복은 새 첨부로만.
 */
class ApplicationDocumentTest {

    private static final Instant NOW = Instant.parse("2026-08-13T03:00:00Z");

    private static ExtractedApplicationForm extracted() {
        return new ExtractedApplicationForm("김계약", "이피보", "종신보험A",
                LocalDate.of(2026, 8, 10), new BigDecimal("1200000"),
                new BigDecimal("100000000"), new BigDecimal("0.93"));
    }

    private static ApplicationDocument newDocument() {
        return ApplicationDocument.extracted("APP-UUID-1", "77", "청약서.jpg", "image/jpeg",
                "hash-abc", 2048L, extracted(), "gemini-2.5-flash", NOW);
    }

    @Test
    @DisplayName("추출 직후는 EXTRACTED — 필수값 보존")
    void createsExtracted() {
        ApplicationDocument document = newDocument();

        assertThat(document.getStatus()).isEqualTo(ApplicationDocumentStatus.EXTRACTED);
        assertThat(document.getApplicationId()).isEqualTo("APP-UUID-1");
        assertThat(document.getUploadedBy()).isEqualTo("77");
        assertThat(document.getExtracted().annualPremium()).isEqualByComparingTo("1200000");
        assertThat(document.getOcrModel()).isEqualTo("gemini-2.5-flash");
        assertThat(document.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("필수값 누락은 생성 거부")
    void rejectsMissingRequired() {
        assertThatThrownBy(() -> ApplicationDocument.extracted(null, "77", "f.jpg", "image/jpeg",
                "h", 1L, extracted(), "m", NOW))
                .isInstanceOf(InvalidApplicationDocumentException.class);
        assertThatThrownBy(() -> ApplicationDocument.extracted("APP-1", " ", "f.jpg", "image/jpeg",
                "h", 1L, extracted(), "m", NOW))
                .isInstanceOf(InvalidApplicationDocumentException.class);
        assertThatThrownBy(() -> ApplicationDocument.extracted("APP-1", "77", "f.jpg", "image/jpeg",
                "h", 0L, extracted(), "m", NOW))
                .isInstanceOf(InvalidApplicationDocumentException.class);
        assertThatThrownBy(() -> ApplicationDocument.extracted("APP-1", "77", "f.jpg", "image/jpeg",
                "h", 1L, null, "m", NOW))
                .isInstanceOf(InvalidApplicationDocumentException.class);
    }

    @Test
    @DisplayName("대사 판정 적용 — MATCHED/MISMATCHED/NEEDS_REVIEW")
    void appliesDecision() {
        ApplicationDocument matched = newDocument();
        matched.applyDecision(DocumentMatchDecision.matched(), NOW.plusSeconds(1));
        assertThat(matched.getStatus()).isEqualTo(ApplicationDocumentStatus.MATCHED);
        assertThat(matched.getMatchNote()).isNull();
        assertThat(matched.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));

        ApplicationDocument mismatched = newDocument();
        mismatched.applyDecision(DocumentMatchDecision.mismatched("연 보험료 불일치"), NOW);
        assertThat(mismatched.getStatus()).isEqualTo(ApplicationDocumentStatus.MISMATCHED);
        assertThat(mismatched.getMatchNote()).isEqualTo("연 보험료 불일치");

        ApplicationDocument review = newDocument();
        review.applyDecision(DocumentMatchDecision.needsReview("신뢰도 미달"), NOW);
        assertThat(review.getStatus()).isEqualTo(ApplicationDocumentStatus.NEEDS_REVIEW);
    }

    @Test
    @DisplayName("종결 이후 재판정은 차단 — 번복은 새 서류 첨부로만")
    void terminalCannotBeReDecided() {
        ApplicationDocument document = newDocument();
        document.applyDecision(DocumentMatchDecision.matched(), NOW);

        assertThatThrownBy(() -> document.applyDecision(DocumentMatchDecision.mismatched("x"), NOW))
                .isInstanceOf(InvalidApplicationDocumentTransitionException.class);
    }

    @Test
    @DisplayName("관리자 리뷰 — NEEDS_REVIEW 에서만 확정/반려, 리뷰어 필수")
    void adminReview() {
        ApplicationDocument document = newDocument();
        document.applyDecision(DocumentMatchDecision.needsReview("보장금액 판독 불가"), NOW);

        document.reviewMatch("99", "육안 대조 완료", NOW.plusSeconds(5));

        assertThat(document.getStatus()).isEqualTo(ApplicationDocumentStatus.MATCHED);
        assertThat(document.getReviewedBy()).isEqualTo("99");
        assertThat(document.getMatchNote()).isEqualTo("육안 대조 완료");
    }

    @Test
    @DisplayName("EXTRACTED 상태에서 관리자 리뷰는 불가 — 자동 대사가 먼저다")
    void reviewRequiresNeedsReview() {
        ApplicationDocument document = newDocument();

        assertThatThrownBy(() -> document.reviewMatch("99", "note", NOW))
                .isInstanceOf(InvalidApplicationDocumentTransitionException.class);
        assertThatThrownBy(() -> document.reviewMismatch("99", "note", NOW))
                .isInstanceOf(InvalidApplicationDocumentTransitionException.class);
    }

    @Test
    @DisplayName("리뷰어 누락은 거부")
    void reviewRequiresReviewer() {
        ApplicationDocument document = newDocument();
        document.applyDecision(DocumentMatchDecision.needsReview("신뢰도 미달"), NOW);

        assertThatThrownBy(() -> document.reviewMatch(" ", "note", NOW))
                .isInstanceOf(InvalidApplicationDocumentException.class);
    }
}
