package github.lms.lemuel.insurance.application.port.in;

/**
 * 대면 상품설명서 렌더링 유스케이스.
 *
 * <p>상품 카탈로그의 현재 조건으로 상품설명서 PDF 를 온디맨드 생성한다 (미리보기·재출력용).
 * 반환된 SHA-256 은 이번에 생성된 바이트의 해시다 — 교부 증빙 해시는 교부 시점 렌더링
 * ({@link RecordDisclosureDeliveryUseCase})이 별도로 고정한다.
 */
public interface RenderProductDisclosureUseCase {

    /**
     * @throws github.lms.lemuel.insurance.domain.exception.ProductNotFoundException
     *         상품이 없거나 판매 종료(inactive) 상태면
     */
    RenderedDisclosure render(String productCode);

    /**
     * @param pdf    렌더링된 PDF 바이트
     * @param sha256 PDF 바이트의 SHA-256 (hex 소문자 64자)
     */
    record RenderedDisclosure(String productCode, String productName, byte[] pdf, String sha256) {

        // 배열 필드라 record 기본 구현은 참조 동일성으로 비교하고 toString 은 [B@... 를 찍는다.
        // 같은 상품설명서를 두 번 렌더링하면 같은 값으로 다뤄져야 하므로 내용 기준으로 재정의한다.
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RenderedDisclosure other)) {
                return false;
            }
            return java.util.Objects.equals(productCode, other.productCode)
                    && java.util.Objects.equals(productName, other.productName)
                    && java.util.Objects.equals(sha256, other.sha256)
                    && java.util.Arrays.equals(pdf, other.pdf);
        }

        @Override
        public int hashCode() {
            return 31 * java.util.Objects.hash(productCode, productName, sha256) + java.util.Arrays.hashCode(pdf);
        }

        @Override
        public String toString() {
            return "RenderedDisclosure[productCode=" + productCode + ", productName=" + productName
                    + ", pdf=" + (pdf == null ? "null" : pdf.length + "B") + ", sha256=" + sha256 + "]";
        }
    }
}
