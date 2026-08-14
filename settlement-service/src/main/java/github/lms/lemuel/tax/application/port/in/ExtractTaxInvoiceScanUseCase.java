package github.lms.lemuel.tax.application.port.in;

import github.lms.lemuel.tax.domain.scan.TaxInvoiceScan;

import java.util.Arrays;
import java.util.Objects;

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

        // 배열 컴포넌트는 레코드 기본 구현이 참조 동일성으로 비교·해시한다 — 같은 파일 재업로드를
        // 멱등으로 다루는 명령에서 "같은 내용"이 같지 않게 나오는 함정이라 내용 기준으로 맞춘다.
        // toString 은 본문 대신 길이만 남긴다(파일 바이트가 로그로 새지 않게).
        @Override
        public boolean equals(Object o) {
            return o instanceof UploadTaxInvoiceScanCommand c
                    && Objects.equals(sellerId, c.sellerId)
                    && Objects.equals(fileName, c.fileName)
                    && Objects.equals(contentType, c.contentType)
                    && Arrays.equals(content, c.content);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(sellerId, fileName, contentType) + Arrays.hashCode(content);
        }

        @Override
        public String toString() {
            return "UploadTaxInvoiceScanCommand[sellerId=" + sellerId + ", fileName=" + fileName
                    + ", contentType=" + contentType
                    + ", contentBytes=" + (content == null ? 0 : content.length) + "]";
        }
    }
}
