package github.lms.lemuel.tax.domain.scan;

/**
 * 대사 판정 결과 — 도달할 상태와 지목한 발행분, 그리고 사람이 읽을 사유.
 *
 * @param status      MATCHED · MISMATCHED · UNMATCHED 중 하나
 * @param taxInvoiceId 지목한 발행 세금계산서(UNMATCHED 면 null)
 * @param reason      MATCHED 가 아닐 때의 사유(조사 단서)
 */
public record ScanMatchDecision(TaxInvoiceScanStatus status, Long taxInvoiceId, String reason) {

    public static ScanMatchDecision matched(Long taxInvoiceId) {
        return new ScanMatchDecision(TaxInvoiceScanStatus.MATCHED, taxInvoiceId, null);
    }

    public static ScanMatchDecision mismatched(Long candidateTaxInvoiceId, String reason) {
        return new ScanMatchDecision(TaxInvoiceScanStatus.MISMATCHED, candidateTaxInvoiceId, reason);
    }

    public static ScanMatchDecision unmatched(String reason) {
        return new ScanMatchDecision(TaxInvoiceScanStatus.UNMATCHED, null, reason);
    }
}
