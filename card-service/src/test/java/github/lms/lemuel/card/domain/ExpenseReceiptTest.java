package github.lms.lemuel.card.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 영수증 애그리거트 — 업로드 1건의 OCR 추출 결과와 대사 상태를 소유한다.
 *
 * <p>멱등 키는 {@code (reportId, fileHash)}(영속 UNIQUE 와 결합) — 같은 파일 재업로드는 새 행도
 * OCR 재호출도 만들지 않는다. 종결(MATCHED/MISMATCHED) 이후 번복은 새 영수증 첨부로만.
 */
class ExpenseReceiptTest {

    private static final Instant NOW = Instant.parse("2026-08-13T03:00:00Z");

    private static ExtractedReceipt extracted() {
        return new ExtractedReceipt("김밥천국", LocalDate.of(2026, 8, 10),
                new BigDecimal("12000"), new BigDecimal("0.93"), new BigDecimal("0.93"));
    }

    private static ExpenseReceipt newReceipt() {
        return ExpenseReceipt.extracted("RPT-1", "CAP-1", 10L, 77L,
                "receipt.jpg", "image/jpeg", "hash-abc", 1024L, extracted(), "gemini-2.5-flash", NOW);
    }

    @Test
    @DisplayName("추출 직후는 EXTRACTED — 필수값 검증")
    void createsExtracted() {
        ExpenseReceipt receipt = newReceipt();

        assertThat(receipt.getStatus()).isEqualTo(ExpenseReceiptStatus.EXTRACTED);
        assertThat(receipt.getReportId()).isEqualTo("RPT-1");
        assertThat(receipt.getCaptureId()).isEqualTo("CAP-1");
        assertThat(receipt.getUploaderUserId()).isEqualTo(77L);
        assertThat(receipt.getExtracted().totalAmount()).isEqualByComparingTo("12000");
        assertThat(receipt.getOcrModel()).isEqualTo("gemini-2.5-flash");
        assertThat(receipt.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("필수값 누락은 생성 거부")
    void rejectsMissingRequired() {
        assertThatThrownBy(() -> ExpenseReceipt.extracted(null, "CAP-1", 10L, 77L,
                "r.jpg", "image/jpeg", "h", 1L, extracted(), "m", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpenseReceipt.extracted("RPT-1", "CAP-1", 10L, 77L,
                " ", "image/jpeg", "h", 1L, extracted(), "m", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpenseReceipt.extracted("RPT-1", "CAP-1", 10L, 77L,
                "r.jpg", "image/jpeg", "h", 0L, extracted(), "m", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpenseReceipt.extracted("RPT-1", "CAP-1", 10L, 77L,
                "r.jpg", "image/jpeg", "h", 1L, null, "m", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("대사 판정 적용 — MATCHED/MISMATCHED/NEEDS_REVIEW")
    void appliesDecision() {
        ExpenseReceipt matched = newReceipt();
        matched.applyDecision(ReceiptMatchDecision.matched(), NOW.plusSeconds(1));
        assertThat(matched.getStatus()).isEqualTo(ExpenseReceiptStatus.MATCHED);
        assertThat(matched.getMatchNote()).isNull();
        assertThat(matched.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));

        ExpenseReceipt mismatched = newReceipt();
        mismatched.applyDecision(ReceiptMatchDecision.mismatched("총액 불일치"), NOW);
        assertThat(mismatched.getStatus()).isEqualTo(ExpenseReceiptStatus.MISMATCHED);
        assertThat(mismatched.getMatchNote()).isEqualTo("총액 불일치");

        ExpenseReceipt review = newReceipt();
        review.applyDecision(ReceiptMatchDecision.needsReview("신뢰도 미달"), NOW);
        assertThat(review.getStatus()).isEqualTo(ExpenseReceiptStatus.NEEDS_REVIEW);
    }

    @Test
    @DisplayName("종결 이후 재판정은 차단 — 번복은 새 영수증 첨부로만")
    void terminalCannotBeReDecided() {
        ExpenseReceipt receipt = newReceipt();
        receipt.applyDecision(ReceiptMatchDecision.matched(), NOW);

        assertThatThrownBy(() -> receipt.applyDecision(ReceiptMatchDecision.mismatched("x"), NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("관리자 리뷰 — NEEDS_REVIEW 에서만 확정/반려 가능")
    void adminReview() {
        ExpenseReceipt receipt = newReceipt();
        receipt.applyDecision(ReceiptMatchDecision.needsReview("신뢰도 미달"), NOW);

        receipt.reviewMatch(99L, "육안 대조 완료", NOW.plusSeconds(5));

        assertThat(receipt.getStatus()).isEqualTo(ExpenseReceiptStatus.MATCHED);
        assertThat(receipt.getReviewedBy()).isEqualTo(99L);
        assertThat(receipt.getMatchNote()).isEqualTo("육안 대조 완료");
    }

    @Test
    @DisplayName("EXTRACTED 상태에서 관리자 리뷰는 불가 — 자동 대사가 먼저다")
    void reviewRequiresNeedsReview() {
        ExpenseReceipt receipt = newReceipt();

        assertThatThrownBy(() -> receipt.reviewMatch(99L, "note", NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> receipt.reviewMismatch(99L, "note", NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("리뷰어 누락은 거부")
    void reviewRequiresReviewer() {
        ExpenseReceipt receipt = newReceipt();
        receipt.applyDecision(ReceiptMatchDecision.needsReview("신뢰도 미달"), NOW);

        assertThatThrownBy(() -> receipt.reviewMatch(null, "note", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
