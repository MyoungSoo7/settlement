package github.lms.lemuel.insurance.adapter.out.persistence;

import github.lms.lemuel.insurance.domain.ApplicationDocument;
import github.lms.lemuel.insurance.domain.ApplicationDocumentStatus;
import github.lms.lemuel.insurance.domain.ExtractedApplicationForm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 청약서류 엔티티 ↔ 도메인 매핑 (ADR 0036).
 *
 * <p>필드가 14개다 — 계약자명↔피보험자명처럼 같은 타입이 나란히 있으면 자리를 바꿔도 컴파일이
 * 통과하고, 종이에 남의 이름이 찍히고 나서야 드러난다. 왕복 매핑으로 그 종류의 실수를 잡는다.
 * 상태 갱신이 파일 본문·추출값을 덮지 않는 것도 함께 고정한다.
 */
class ApplicationDocumentJpaEntityTest {

    private static final Instant NOW = Instant.parse("2026-08-14T01:00:00Z");
    private static final byte[] CONTENT = "pdf-bytes".getBytes(StandardCharsets.UTF_8);
    private static final String APPLICATION_ID = UUID.randomUUID().toString();

    private static ExtractedApplicationForm form() {
        return new ExtractedApplicationForm("홍길동", "홍길순", "무배당 종신보험",
                LocalDate.of(2026, 8, 10), new BigDecimal("360000"), new BigDecimal("100000000"),
                new BigDecimal("0.94"));
    }

    private static ApplicationDocument document() {
        return ApplicationDocument.builder()
                .id(11L)
                .applicationId(APPLICATION_ID)
                .uploadedBy("77")
                .fileName("청약서.pdf")
                .contentType("application/pdf")
                .fileHash("hash-abc")
                .sizeBytes(4096L)
                .extracted(form())
                .ocrModel("gemini-2.5-flash")
                .status(ApplicationDocumentStatus.EXTRACTED)
                .matchNote(null)
                .reviewedBy(null)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    @Test
    @DisplayName("도메인 → 엔티티 → 도메인 왕복에서 식별·파일 메타가 보존된다")
    void roundTripsIdentityAndFileMeta() {
        ApplicationDocument restored = ApplicationDocumentJpaEntity.fromDomain(document(), CONTENT).toDomain();

        assertThat(restored.getId()).isEqualTo(11L);
        assertThat(restored.getApplicationId()).isEqualTo(APPLICATION_ID);
        assertThat(restored.getUploadedBy()).isEqualTo("77");
        assertThat(restored.getFileName()).isEqualTo("청약서.pdf");
        assertThat(restored.getContentType()).isEqualTo("application/pdf");
        assertThat(restored.getFileHash()).isEqualTo("hash-abc");
        assertThat(restored.getSizeBytes()).isEqualTo(4096L);
        assertThat(restored.getOcrModel()).isEqualTo("gemini-2.5-flash");
        assertThat(restored.getStatus()).isEqualTo(ApplicationDocumentStatus.EXTRACTED);
        assertThat(restored.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("계약자·피보험자·상품명·금액이 자리를 바꾸지 않고 복원된다")
    void roundTripsExtractedValues() {
        ApplicationDocument restored = ApplicationDocumentJpaEntity.fromDomain(document(), CONTENT).toDomain();

        ExtractedApplicationForm e = restored.getExtracted();
        assertThat(e.contractorName()).isEqualTo("홍길동");
        assertThat(e.insuredName()).isEqualTo("홍길순");
        assertThat(e.productName()).isEqualTo("무배당 종신보험");
        assertThat(e.applicationDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(e.annualPremium()).isEqualByComparingTo("360000");
        assertThat(e.coverageAmount()).isEqualByComparingTo("100000000");
        assertThat(e.confidence()).isEqualByComparingTo("0.94");
    }

    @Test
    @DisplayName("리뷰 종결은 상태·사유·리뷰어·시각만 갱신하고 추출값은 건드리지 않는다")
    void appliesOnlyStateFields() {
        ApplicationDocumentJpaEntity entity = ApplicationDocumentJpaEntity.fromDomain(document(), CONTENT);

        ApplicationDocument reviewed = ApplicationDocument.builder()
                .id(11L)
                .applicationId(APPLICATION_ID)
                .uploadedBy("77")
                .fileName("청약서.pdf")
                .contentType("application/pdf")
                .fileHash("hash-abc")
                .sizeBytes(4096L)
                .extracted(form())
                .ocrModel("gemini-2.5-flash")
                .status(ApplicationDocumentStatus.MISMATCHED)
                .matchNote("성명 불일치")
                .reviewedBy("99")
                .createdAt(NOW)
                .updatedAt(NOW.plusSeconds(3600))
                .build();
        entity.applyStateFrom(reviewed);

        ApplicationDocument restored = entity.toDomain();
        assertThat(restored.getStatus()).isEqualTo(ApplicationDocumentStatus.MISMATCHED);
        assertThat(restored.getMatchNote()).isEqualTo("성명 불일치");
        assertThat(restored.getReviewedBy()).isEqualTo("99");
        assertThat(restored.getUpdatedAt()).isEqualTo(NOW.plusSeconds(3600));
        // 불변 영역
        assertThat(restored.getFileHash()).isEqualTo("hash-abc");
        assertThat(restored.getExtracted().annualPremium()).isEqualByComparingTo("360000");
        assertThat(restored.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("가입금액이 판독되지 않은 서류도 그대로 왕복한다 (0 으로 채우지 않는다)")
    void keepsNullCoverageAmount() {
        ApplicationDocument noCoverage = ApplicationDocument.builder()
                .id(12L)
                .applicationId(APPLICATION_ID)
                .uploadedBy("77")
                .fileName("청약서.pdf")
                .contentType("application/pdf")
                .fileHash("hash-x")
                .sizeBytes(1L)
                .extracted(new ExtractedApplicationForm("홍길동", "홍길순", "무배당 종신보험",
                        LocalDate.of(2026, 8, 10), new BigDecimal("360000"), null, new BigDecimal("0.5")))
                .ocrModel("m")
                .status(ApplicationDocumentStatus.NEEDS_REVIEW)
                .matchNote(null)
                .reviewedBy(null)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();

        ApplicationDocument restored = ApplicationDocumentJpaEntity.fromDomain(noCoverage, CONTENT).toDomain();

        assertThat(restored.getExtracted().coverageAmount()).isNull();
        assertThat(restored.getStatus()).isEqualTo(ApplicationDocumentStatus.NEEDS_REVIEW);
        assertThat(restored.getMatchNote()).isNull();
        assertThat(restored.getReviewedBy()).isNull();
    }
}
