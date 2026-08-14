package github.lms.lemuel.deposit.domain;

import github.lms.lemuel.deposit.domain.exception.InvalidDepositProofException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 예치금 증빙 애그리거트 — (sellerId, referenceType, referenceId, fileHash) 멱등, 전이표 강제,
 * 종결 번복은 새 첨부로만.
 */
class DepositProofTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 14, 10, 0);

    private static ExtractedTransferProof extracted() {
        return new ExtractedTransferProof("홍길동", LocalDate.of(2026, 8, 12),
                new BigDecimal("3000000"), new BigDecimal("0.93"));
    }

    private static DepositProof newProof() {
        return DepositProof.extracted(7L, "MANUAL_TOPUP", "TOPUP-2026-0814-001", 99L,
                "이체확인증.png", "image/png", "hash-abc", 2048L, extracted(), "gemini-2.5-flash", NOW);
    }

    @Test
    @DisplayName("추출 직후는 EXTRACTED(기표 대기) — 필수값 보존")
    void createsExtracted() {
        DepositProof proof = newProof();

        assertThat(proof.getStatus()).isEqualTo(DepositProofStatus.EXTRACTED);
        assertThat(proof.getSellerId()).isEqualTo(7L);
        assertThat(proof.getReferenceType()).isEqualTo("MANUAL_TOPUP");
        assertThat(proof.getReferenceId()).isEqualTo("TOPUP-2026-0814-001");
        assertThat(proof.getUploadedBy()).isEqualTo(99L);
        assertThat(proof.getExtracted().transferAmount()).isEqualByComparingTo("3000000");
        assertThat(proof.getOcrModel()).isEqualTo("gemini-2.5-flash");
        assertThat(proof.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("필수값 누락은 생성 거부")
    void rejectsMissingRequired() {
        assertThatThrownBy(() -> DepositProof.extracted(null, "MANUAL_TOPUP", "R-1", 99L,
                "f.png", "image/png", "h", 1L, extracted(), "m", NOW))
                .isInstanceOf(InvalidDepositProofException.class);
        assertThatThrownBy(() -> DepositProof.extracted(7L, " ", "R-1", 99L,
                "f.png", "image/png", "h", 1L, extracted(), "m", NOW))
                .isInstanceOf(InvalidDepositProofException.class);
        assertThatThrownBy(() -> DepositProof.extracted(7L, "MANUAL_TOPUP", " ", 99L,
                "f.png", "image/png", "h", 1L, extracted(), "m", NOW))
                .isInstanceOf(InvalidDepositProofException.class);
        assertThatThrownBy(() -> DepositProof.extracted(7L, "MANUAL_TOPUP", "R-1", 99L,
                "f.png", "image/png", "h", 1L, null, "m", NOW))
                .isInstanceOf(InvalidDepositProofException.class);
    }

    @Test
    @DisplayName("대사 판정 적용 — MATCHED/MISMATCHED/NEEDS_REVIEW")
    void appliesDecision() {
        DepositProof matched = newProof();
        matched.applyDecision(DepositProofMatchDecision.matched(), NOW.plusSeconds(1));
        assertThat(matched.getStatus()).isEqualTo(DepositProofStatus.MATCHED);
        assertThat(matched.getMatchNote()).isNull();
        assertThat(matched.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));

        DepositProof mismatched = newProof();
        mismatched.applyDecision(DepositProofMatchDecision.mismatched("이체금액 불일치"), NOW);
        assertThat(mismatched.getStatus()).isEqualTo(DepositProofStatus.MISMATCHED);
        assertThat(mismatched.getMatchNote()).isEqualTo("이체금액 불일치");

        DepositProof review = newProof();
        review.applyDecision(DepositProofMatchDecision.needsReview("신뢰도 미달"), NOW);
        assertThat(review.getStatus()).isEqualTo(DepositProofStatus.NEEDS_REVIEW);
    }

    @Test
    @DisplayName("종결 이후 재판정은 차단 — 번복은 새 증빙 첨부로만")
    void terminalCannotBeReDecided() {
        DepositProof proof = newProof();
        proof.applyDecision(DepositProofMatchDecision.matched(), NOW);

        assertThatThrownBy(() -> proof.applyDecision(DepositProofMatchDecision.mismatched("x"), NOW))
                .isInstanceOf(InvalidDepositProofException.class);
    }

    @Test
    @DisplayName("운영자 리뷰 — NEEDS_REVIEW 에서만 확정/반려, 리뷰어 필수")
    void operatorReview() {
        DepositProof proof = newProof();
        proof.applyDecision(DepositProofMatchDecision.needsReview("이체일 판독 불가"), NOW);

        proof.reviewMatch(11L, "은행 앱 육안 대조 완료", NOW.plusMinutes(5));

        assertThat(proof.getStatus()).isEqualTo(DepositProofStatus.MATCHED);
        assertThat(proof.getReviewedBy()).isEqualTo(11L);
        assertThat(proof.getMatchNote()).isEqualTo("은행 앱 육안 대조 완료");
    }

    @Test
    @DisplayName("EXTRACTED 상태에서 리뷰는 불가, 리뷰어 누락도 거부")
    void reviewGuards() {
        DepositProof proof = newProof();
        assertThatThrownBy(() -> proof.reviewMatch(11L, "note", NOW))
                .isInstanceOf(InvalidDepositProofException.class);

        DepositProof needsReview = newProof();
        needsReview.applyDecision(DepositProofMatchDecision.needsReview("신뢰도 미달"), NOW);
        assertThatThrownBy(() -> needsReview.reviewMatch(null, "note", NOW))
                .isInstanceOf(InvalidDepositProofException.class);
    }
}
