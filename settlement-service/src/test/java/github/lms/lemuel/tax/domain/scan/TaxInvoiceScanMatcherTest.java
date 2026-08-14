package github.lms.lemuel.tax.domain.scan;

import github.lms.lemuel.tax.domain.TaxInvoice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스캔본 ↔ 발행 세금계산서 대사 규칙 (순수 도메인 — 포트/DB 없이 판정만).
 */
class TaxInvoiceScanMatcherTest {

    private static final LocalDate WRITTEN = LocalDate.of(2026, 8, 1);

    private static ExtractedTaxInvoice scanned(String supply, String tax, String total, String approvalNumber) {
        return ExtractedTaxInvoice.of("101-81-00001", "101-81-00001", WRITTEN,
                new BigDecimal(supply), new BigDecimal(tax), new BigDecimal(total), approvalNumber,
                new BigDecimal("0.95"));
    }

    private static TaxInvoice issued(Long sellerId, String supply, String tax) {
        BigDecimal supplyAmount = new BigDecimal(supply);
        BigDecimal taxAmount = new BigDecimal(tax);
        return TaxInvoice.rehydrate(42L, 5L, sellerId, supplyAmount, taxAmount,
                supplyAmount.add(taxAmount), WRITTEN, TaxInvoice.numberFor(5L),
                LocalDateTime.of(2026, 8, 1, 12, 0));
    }

    @Test
    @DisplayName("승인번호에서 정산 식별자를 되읽는다 — 발행번호 규칙의 역함수")
    void resolvesSettlementIdFromApprovalNumber() {
        assertThat(TaxInvoiceScanMatcher.settlementIdFrom("TI-0000000005")).isEqualTo(5L);
        assertThat(TaxInvoiceScanMatcher.settlementIdFrom(" TI-0000000005 ")).isEqualTo(5L);
    }

    @Test
    @DisplayName("우리 발행번호 형식이 아니면 정산 식별자를 만들지 않는다 (오매칭 방지)")
    void rejectsForeignApprovalNumbers() {
        assertThat(TaxInvoiceScanMatcher.settlementIdFrom(null)).isNull();
        assertThat(TaxInvoiceScanMatcher.settlementIdFrom("")).isNull();
        assertThat(TaxInvoiceScanMatcher.settlementIdFrom("20260801-12345678")).isNull();
        assertThat(TaxInvoiceScanMatcher.settlementIdFrom("TI-5")).isNull();          // 자리수 규칙 불일치
        assertThat(TaxInvoiceScanMatcher.settlementIdFrom("XX-0000000005")).isNull();
    }

    @Test
    @DisplayName("금액 3종이 모두 같으면 MATCHED")
    void allAmountsEqual() {
        ScanMatchDecision decision =
                TaxInvoiceScanMatcher.decide(scanned("100000", "10000", "110000", "TI-0000000005"),
                        7L, issued(7L, "100000", "10000"));

        assertThat(decision.status()).isEqualTo(TaxInvoiceScanStatus.MATCHED);
        assertThat(decision.taxInvoiceId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("scale 차이는 불일치가 아니다 (100000 vs 100000.00)")
    void scaleDifferenceIsNotMismatch() {
        ScanMatchDecision decision =
                TaxInvoiceScanMatcher.decide(scanned("100000.00", "10000.00", "110000.00", "TI-0000000005"),
                        7L, issued(7L, "100000", "10000"));

        assertThat(decision.status()).isEqualTo(TaxInvoiceScanStatus.MATCHED);
    }

    @Test
    @DisplayName("금액이 다르면 MISMATCHED — 사유에 어느 항목이 얼마나 다른지 남긴다")
    void amountMismatch() {
        ScanMatchDecision decision =
                TaxInvoiceScanMatcher.decide(scanned("100000", "10000", "110000", "TI-0000000005"),
                        7L, issued(7L, "90000", "9000"));

        assertThat(decision.status()).isEqualTo(TaxInvoiceScanStatus.MISMATCHED);
        assertThat(decision.taxInvoiceId()).isEqualTo(42L);
        assertThat(decision.reason()).contains("공급가액").contains("100000").contains("90000");
    }

    @Test
    @DisplayName("대응 발행분이 없으면 UNMATCHED")
    void noCandidate() {
        ScanMatchDecision decision =
                TaxInvoiceScanMatcher.decide(scanned("100000", "10000", "110000", "TI-0000000005"), 7L, null);

        assertThat(decision.status()).isEqualTo(TaxInvoiceScanStatus.UNMATCHED);
        assertThat(decision.taxInvoiceId()).isNull();
    }

    @Test
    @DisplayName("★ 남의 셀러 발행분과는 대사하지 않는다 (IDOR 방어 — 스캔 소유자와 발행 소유자 대조)")
    void refusesCrossSellerMatch() {
        ScanMatchDecision decision =
                TaxInvoiceScanMatcher.decide(scanned("100000", "10000", "110000", "TI-0000000005"),
                        7L, issued(99L, "100000", "10000"));

        assertThat(decision.status()).isEqualTo(TaxInvoiceScanStatus.UNMATCHED);
        assertThat(decision.taxInvoiceId()).isNull();
        assertThat(decision.reason()).contains("소유");
    }
}
