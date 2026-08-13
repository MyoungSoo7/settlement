package github.lms.lemuel.tax.domain.scan;

import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;
import github.lms.lemuel.tax.domain.exception.TaxInvoiceScanStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 세금계산서 스캔 애그리거트 — 상태 전이는 전이 메서드로만, 종결 상태는 되돌릴 수 없다.
 */
class TaxInvoiceScanTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 11, 3, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime LATER = NOW.plusHours(2);

    private static ExtractedTaxInvoice fields() {
        return ExtractedTaxInvoice.of("101-81-00001", "101-81-00001", LocalDate.of(2026, 8, 1),
                new BigDecimal("100000"), new BigDecimal("10000"), new BigDecimal("110000"),
                "TI-0000000005", new BigDecimal("0.96"));
    }

    private static TaxInvoiceScan scan() {
        return TaxInvoiceScan.extracted(7L, "invoice.png", "image/png", "a".repeat(64), 2048L,
                fields(), "gemini-2.0-flash", NOW);
    }

    @Test
    @DisplayName("추출 직후 상태는 EXTRACTED — 아직 대사되지 않았다")
    void newlyExtracted() {
        TaxInvoiceScan scan = scan();

        assertThat(scan.getStatus()).isEqualTo(TaxInvoiceScanStatus.EXTRACTED);
        assertThat(scan.getSellerId()).isEqualTo(7L);
        assertThat(scan.getLinkedTaxInvoiceId()).isNull();
        assertThat(scan.getCreatedAt()).isEqualTo(NOW);
        assertThat(scan.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("대사 성공 → MATCHED, 매칭된 발행분 id 를 못박는다")
    void matched() {
        TaxInvoiceScan scan = scan();

        scan.matchTo(42L, LATER);

        assertThat(scan.getStatus()).isEqualTo(TaxInvoiceScanStatus.MATCHED);
        assertThat(scan.getLinkedTaxInvoiceId()).isEqualTo(42L);
        assertThat(scan.getUpdatedAt()).isEqualTo(LATER);
    }

    @Test
    @DisplayName("금액 불일치 → MISMATCHED, 사유와 후보를 남긴다")
    void mismatched() {
        TaxInvoiceScan scan = scan();

        scan.markMismatched(42L, "공급가액 불일치: 스캔 100000 vs 발행 90000", LATER);

        assertThat(scan.getStatus()).isEqualTo(TaxInvoiceScanStatus.MISMATCHED);
        // 후보 발행분은 남긴다(조사 단서) — 다만 MATCHED 가 아니므로 확정 대사는 아니다
        assertThat(scan.getLinkedTaxInvoiceId()).isEqualTo(42L);
        assertThat(scan.getReviewNote()).contains("공급가액 불일치");
    }

    @Test
    @DisplayName("대응 발행분 없음 → UNMATCHED")
    void unmatched() {
        TaxInvoiceScan scan = scan();

        scan.markUnmatched("승인번호로 발행분을 찾지 못했습니다", LATER);

        assertThat(scan.getStatus()).isEqualTo(TaxInvoiceScanStatus.UNMATCHED);
    }

    @Test
    @DisplayName("MISMATCHED·UNMATCHED 는 재대사로 MATCHED 가 될 수 있다")
    void rematchAllowed() {
        TaxInvoiceScan mismatched = scan();
        mismatched.markMismatched(42L, "사유", LATER);
        mismatched.matchTo(42L, LATER);
        assertThat(mismatched.getStatus()).isEqualTo(TaxInvoiceScanStatus.MATCHED);

        TaxInvoiceScan unmatched = scan();
        unmatched.markUnmatched("사유", LATER);
        unmatched.matchTo(43L, LATER);
        assertThat(unmatched.getStatus()).isEqualTo(TaxInvoiceScanStatus.MATCHED);
    }

    @Test
    @DisplayName("반려는 어느 미종결 상태에서든 가능하다")
    void rejectFromOpenStates() {
        TaxInvoiceScan scan = scan();
        scan.reject("위조 의심", LATER);

        assertThat(scan.getStatus()).isEqualTo(TaxInvoiceScanStatus.REJECTED);
        assertThat(scan.getReviewNote()).isEqualTo("위조 의심");
    }

    @Test
    @DisplayName("MATCHED 는 종결 — 이후 어떤 전이도 거부한다")
    void matchedIsTerminal() {
        TaxInvoiceScan scan = scan();
        scan.matchTo(42L, LATER);

        assertThatThrownBy(() -> scan.reject("번복", LATER))
                .isInstanceOf(TaxInvoiceScanStateException.class)
                .hasMessageContaining("MATCHED");
        assertThatThrownBy(() -> scan.markUnmatched("번복", LATER))
                .isInstanceOf(TaxInvoiceScanStateException.class);
    }

    @Test
    @DisplayName("REJECTED 는 종결 — 재대사 불가")
    void rejectedIsTerminal() {
        TaxInvoiceScan scan = scan();
        scan.reject("위조 의심", LATER);

        assertThatThrownBy(() -> scan.matchTo(42L, LATER))
                .isInstanceOf(TaxInvoiceScanStateException.class);
    }

    @Test
    @DisplayName("상태 전이표 — canTransitionTo 가 정본")
    void transitionTable() {
        assertThat(TaxInvoiceScanStatus.EXTRACTED.canTransitionTo(TaxInvoiceScanStatus.MATCHED)).isTrue();
        assertThat(TaxInvoiceScanStatus.EXTRACTED.canTransitionTo(TaxInvoiceScanStatus.EXTRACTED)).isFalse();
        assertThat(TaxInvoiceScanStatus.MISMATCHED.canTransitionTo(TaxInvoiceScanStatus.UNMATCHED)).isFalse();
        assertThat(TaxInvoiceScanStatus.MATCHED.isTerminal()).isTrue();
        assertThat(TaxInvoiceScanStatus.REJECTED.isTerminal()).isTrue();
        assertThat(TaxInvoiceScanStatus.EXTRACTED.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("필수 식별자·파일 해시 누락은 예외")
    void requiredFields() {
        assertThatThrownBy(() -> TaxInvoiceScan.extracted(null, "f.png", "image/png", "a".repeat(64),
                1L, fields(), "m", NOW))
                .isInstanceOf(TaxInvariantViolationException.class)
                .hasMessageContaining("sellerId");

        assertThatThrownBy(() -> TaxInvoiceScan.extracted(7L, "f.png", "image/png", " ",
                1L, fields(), "m", NOW))
                .isInstanceOf(TaxInvariantViolationException.class)
                .hasMessageContaining("해시");

        assertThatThrownBy(() -> TaxInvoiceScan.extracted(7L, "f.png", "image/png", "a".repeat(64),
                1L, null, "m", NOW))
                .isInstanceOf(TaxInvariantViolationException.class)
                .hasMessageContaining("추출 결과");
    }

    @Test
    @DisplayName("id 는 1회만 부여된다 (write-once)")
    void assignIdOnce() {
        TaxInvoiceScan scan = scan();
        scan.assignId(9L);

        assertThat(scan.getId()).isEqualTo(9L);
        assertThatThrownBy(() -> scan.assignId(10L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("rehydrate 는 영속 상태를 그대로 복원한다")
    void rehydrate() {
        TaxInvoiceScan scan = TaxInvoiceScan.rehydrate(3L, 7L, "f.png", "image/png", "b".repeat(64),
                10L, fields(), "gemini", TaxInvoiceScanStatus.MISMATCHED, 42L, "사유", NOW, LATER);

        assertThat(scan.getId()).isEqualTo(3L);
        assertThat(scan.getStatus()).isEqualTo(TaxInvoiceScanStatus.MISMATCHED);
        assertThat(scan.getUpdatedAt()).isEqualTo(LATER);
    }
}
