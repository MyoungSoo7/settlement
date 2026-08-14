package github.lms.lemuel.tax.adapter.in.web;

import github.lms.lemuel.tax.domain.scan.ExtractedTaxInvoice;
import github.lms.lemuel.tax.domain.scan.TaxInvoiceScan;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 스캔 응답 뷰 — 사업자등록번호는 <b>마스킹된 값만</b> 싣는다(PII), 원문은 저장 계층에만 남는다.
 *
 * <p>{@code needsReview}·{@code totalConsistent}·{@code vatConsistent} 를 함께 내려 클라이언트가
 * "AI 가 읽었으니 끝"이 아니라 <b>사람이 확인해야 하는 건</b>을 구분할 수 있게 한다.
 */
public record TaxInvoiceScanView(Long id,
                                 String status,
                                 String fileName,
                                 String ocrModel,
                                 String supplierBusinessNo,
                                 String buyerBusinessNo,
                                 String writtenDate,
                                 BigDecimal supplyAmount,
                                 BigDecimal taxAmount,
                                 BigDecimal totalAmount,
                                 String approvalNumber,
                                 BigDecimal confidence,
                                 boolean needsReview,
                                 boolean totalConsistent,
                                 boolean vatConsistent,
                                 Long linkedTaxInvoiceId,
                                 String reviewNote,
                                 OffsetDateTime createdAt) {

    /** 기본 리뷰 임계값 — 서버 설정과 별개로 응답에 "사람 확인 필요" 힌트를 주기 위한 표시용 기준. */
    private static final BigDecimal REVIEW_THRESHOLD = new BigDecimal("0.80");

    public static TaxInvoiceScanView of(TaxInvoiceScan scan) {
        ExtractedTaxInvoice e = scan.getExtracted();
        return new TaxInvoiceScanView(
                scan.getId(),
                scan.getStatus().name(),
                scan.getFileName(),
                scan.getOcrModel(),
                e.supplier().masked(),
                e.buyer().masked(),
                e.writtenDate().toString(),
                e.supplyAmount(),
                e.taxAmount(),
                e.totalAmount(),
                e.approvalNumber(),
                e.confidence(),
                e.needsReview(REVIEW_THRESHOLD),
                e.totalConsistent(),
                e.vatConsistent(),
                scan.getLinkedTaxInvoiceId(),
                scan.getReviewNote(),
                scan.getCreatedAt());
    }
}
