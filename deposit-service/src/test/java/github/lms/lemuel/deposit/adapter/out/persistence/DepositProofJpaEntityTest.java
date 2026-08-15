package github.lms.lemuel.deposit.adapter.out.persistence;

import github.lms.lemuel.deposit.domain.DepositProof;
import github.lms.lemuel.deposit.domain.DepositProofMatchDecision;
import github.lms.lemuel.deposit.domain.DepositProofStatus;
import github.lms.lemuel.deposit.domain.ExtractedTransferProof;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 증빙 엔티티 ↔ 도메인 매핑.
 *
 * <p>필드가 15개라 순서만 어긋나도(예: fileName↔contentType) 컴파일은 통과하고 데이터만 조용히 섞인다.
 * 왕복 매핑으로 그 종류의 실수를 잡는다. 파일 본문·추출값이 상태 변경에서 덮이지 않는 것도 함께 고정한다.
 */
class DepositProofJpaEntityTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 14, 10, 0);
    private static final byte[] CONTENT = "png-bytes".getBytes(StandardCharsets.UTF_8);

    private static ExtractedTransferProof extracted() {
        return new ExtractedTransferProof("홍길동", LocalDate.of(2026, 8, 12),
                new BigDecimal("3000000"), new BigDecimal("0.93"));
    }

    private static DepositProof persistedProof() {
        return DepositProof.builder()
                .id(11L)
                .sellerId(7L)
                .referenceType("MANUAL_TOPUP")
                .referenceId("TOPUP-2026-0814-001")
                .uploadedBy(99L)
                .fileName("이체확인증.png")
                .contentType("image/png")
                .fileHash("hash-abc")
                .sizeBytes(2048L)
                .extracted(extracted())
                .ocrModel("gemini-2.5-flash")
                .status(DepositProofStatus.EXTRACTED)
                .matchNote(null)
                .reviewedBy(null)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    @Test
    @DisplayName("도메인 → 엔티티 → 도메인 왕복에서 모든 필드가 보존된다")
    void roundTripsAllFields() {
        DepositProof origin = persistedProof();

        DepositProof restored = DepositProofJpaEntity.fromDomain(origin, CONTENT).toDomain();

        assertThat(restored.getId()).isEqualTo(11L);
        assertThat(restored.getSellerId()).isEqualTo(7L);
        assertThat(restored.getReferenceType()).isEqualTo("MANUAL_TOPUP");
        assertThat(restored.getReferenceId()).isEqualTo("TOPUP-2026-0814-001");
        assertThat(restored.getUploadedBy()).isEqualTo(99L);
        assertThat(restored.getFileName()).isEqualTo("이체확인증.png");
        assertThat(restored.getContentType()).isEqualTo("image/png");
        assertThat(restored.getFileHash()).isEqualTo("hash-abc");
        assertThat(restored.getSizeBytes()).isEqualTo(2048L);
        assertThat(restored.getOcrModel()).isEqualTo("gemini-2.5-flash");
        assertThat(restored.getStatus()).isEqualTo(DepositProofStatus.EXTRACTED);
        assertThat(restored.getCreatedAt()).isEqualTo(NOW);
        assertThat(restored.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("OCR 추출값(입금자·이체일·금액·신뢰도)도 그대로 복원된다")
    void roundTripsExtractedValues() {
        DepositProof restored = DepositProofJpaEntity.fromDomain(persistedProof(), CONTENT).toDomain();

        ExtractedTransferProof e = restored.getExtracted();
        assertThat(e.senderName()).isEqualTo("홍길동");
        assertThat(e.transferDate()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(e.transferAmount()).isEqualByComparingTo("3000000");
        assertThat(e.confidence()).isEqualByComparingTo("0.93");
    }

    @Test
    @DisplayName("판정 결과(상태·사유·리뷰어·시각)만 갱신하고 파일 본문·추출값은 건드리지 않는다")
    void appliesOnlyStateFields() {
        DepositProof proof = persistedProof();
        DepositProofJpaEntity entity = DepositProofJpaEntity.fromDomain(proof, CONTENT);

        DepositProof decided = persistedProof();
        decided.applyDecision(DepositProofMatchDecision.mismatched("이체금액 불일치"), NOW.plusHours(1));
        entity.applyStateFrom(decided);

        DepositProof restored = entity.toDomain();
        assertThat(restored.getStatus()).isEqualTo(DepositProofStatus.MISMATCHED);
        assertThat(restored.getMatchNote()).isEqualTo("이체금액 불일치");
        assertThat(restored.getUpdatedAt()).isEqualTo(NOW.plusHours(1));
        // 불변 영역
        assertThat(restored.getFileHash()).isEqualTo("hash-abc");
        assertThat(restored.getSizeBytes()).isEqualTo(2048L);
        assertThat(restored.getExtracted().transferAmount()).isEqualByComparingTo("3000000");
        assertThat(restored.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("선택값(입금자명·이체일·사유·리뷰어)이 비어 있어도 매핑된다")
    void mapsNullableFields() {
        DepositProof proof = DepositProof.builder()
                .id(12L)
                .sellerId(7L)
                .referenceType("MANUAL_TOPUP")
                .referenceId("TOPUP-2")
                .uploadedBy(99L)
                .fileName("f.png")
                .contentType("image/png")
                .fileHash("h2")
                .sizeBytes(1L)
                // 이체금액은 양수 강제(도메인 불변식) — 판독 실패는 입금자명·이체일만 null 로 표현한다
                .extracted(new ExtractedTransferProof(null, null, new BigDecimal("1"), BigDecimal.ZERO))
                .ocrModel("m")
                .status(DepositProofStatus.NEEDS_REVIEW)
                .matchNote(null)
                .reviewedBy(null)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();

        DepositProof restored = DepositProofJpaEntity.fromDomain(proof, CONTENT).toDomain();

        assertThat(restored.getExtracted().senderName()).isNull();
        assertThat(restored.getExtracted().transferDate()).isNull();
        assertThat(restored.getMatchNote()).isNull();
        assertThat(restored.getReviewedBy()).isNull();
        assertThat(restored.getStatus()).isEqualTo(DepositProofStatus.NEEDS_REVIEW);
    }
}
