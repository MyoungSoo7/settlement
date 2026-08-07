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
    }
}
