package github.lms.lemuel.tax.application.port.in;

import github.lms.lemuel.tax.domain.scan.TaxInvoiceScan;

/**
 * 세금계산서 스캔본 업로드 → OCR 추출 → 자동 대사.
 *
 * <p>같은 셀러가 같은 파일을 다시 올리면 <b>새 추출 없이</b> 기존 스캔을 돌려준다(멱등 — AI 호출 비용 절감).
 */
public interface ExtractTaxInvoiceScanUseCase {

    TaxInvoiceScan extract(UploadTaxInvoiceScanCommand command);

    /**
     * @param sellerId JWT 주체(userId)에서 파생한 값이어야 한다 — 요청 본문의 셀러 식별자를 신뢰하지 않는다
     */
    record UploadTaxInvoiceScanCommand(Long sellerId, String fileName, String contentType, byte[] content) {
    }
}
