package github.lms.lemuel.report.adapter.out.pdf;

import github.lms.lemuel.report.domain.BucketGranularity;
import github.lms.lemuel.report.domain.CashflowBucket;
import github.lms.lemuel.report.domain.CashflowReconciliation;
import github.lms.lemuel.report.domain.CashflowReport;
import github.lms.lemuel.report.domain.ReconciliationCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cashflow PDF 어댑터 단위 테스트.
 * WebMvcTest 슬라이스에서 binary response 처리는 Boot 4 이슈가 있어(설정 파일 TODO 참조),
 * 여기선 어댑터가 유효한 PDF 바이너리를 생성하는지만 magic bytes 로 검증한다.
 */
class CashflowPdfAdapterTest {

    private final CashflowPdfAdapter adapter = new CashflowPdfAdapter();

    @Test
    @DisplayName("매치된 리포트 — 유효한 PDF 바이너리 생성")
    void rendersValidPdfForMatchedReport() {
        CashflowReport report = buildReport(/*matched*/ true);

        byte[] pdf = adapter.render(report);

        assertPdfMagic(pdf);
    }

    @Test
    @DisplayName("대사 실패 리포트 — PDF 생성 정상 (FAIL 섹션 포함)")
    void rendersValidPdfForMismatchedReport() {
        CashflowReport report = buildReport(/*matched*/ false);

        byte[] pdf = adapter.render(report);

        assertPdfMagic(pdf);
    }

    @Test
    @DisplayName("빈 버킷 — PDF 생성은 성공 (빈 기간 메시지)")
    void rendersPdfWithEmptyBuckets() {
        CashflowReport report = CashflowReport.of(
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 1),
                BucketGranularity.DAY, List.of(),
                CashflowReconciliation.empty());

        byte[] pdf = adapter.render(report);

        assertPdfMagic(pdf);
    }

    private static void assertPdfMagic(byte[] pdf) {
        assertThat(pdf).isNotNull();
        assertThat(pdf.length).isGreaterThan(100);
        // PDF magic: %PDF
        assertThat(pdf[0]).isEqualTo((byte) 0x25); // %
        assertThat(pdf[1]).isEqualTo((byte) 0x50); // P
        assertThat(pdf[2]).isEqualTo((byte) 0x44); // D
        assertThat(pdf[3]).isEqualTo((byte) 0x46); // F
    }

    private CashflowReport buildReport(boolean matched) {
        List<CashflowBucket> buckets = List.of(
                new CashflowBucket(LocalDate.of(2026, 4, 1), 2,
                        new BigDecimal("50000"), BigDecimal.ZERO,
                        new BigDecimal("1500"), new BigDecimal("48500")),
                new CashflowBucket(LocalDate.of(2026, 4, 2), 3,
                        new BigDecimal("100000"), new BigDecimal("10000"),
                        new BigDecimal("3000"), new BigDecimal("87000"))
        );
        CashflowReconciliation recon = matched
                ? CashflowReconciliation.of(List.of(
                        ReconciliationCheck.of("inv1",
                                new BigDecimal("140000"), new BigDecimal("140000"), "ok"),
                        ReconciliationCheck.of("inv2",
                                new BigDecimal("0"), new BigDecimal("0"), "ok"),
                        ReconciliationCheck.of("inv3",
                                BigDecimal.valueOf(5), BigDecimal.valueOf(5), "ok")))
                : CashflowReconciliation.of(List.of(
                        ReconciliationCheck.of("inv1",
                                new BigDecimal("100"), new BigDecimal("99"), "diff=1"),
                        ReconciliationCheck.of("inv2",
                                new BigDecimal("0"), new BigDecimal("0"), "ok")));
        return CashflowReport.of(
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30),
                BucketGranularity.DAY, buckets, recon);
    }
}
