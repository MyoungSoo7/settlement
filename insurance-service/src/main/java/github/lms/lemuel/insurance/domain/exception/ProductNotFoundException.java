package github.lms.lemuel.insurance.domain.exception;

/**
 * 상품 카탈로그에 없는(또는 판매 종료된) 상품 코드로 상품설명서를 요청한 경우.
 *
 * <p>웹 어댑터가 404 로 매핑한다.
 */
public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String productCode) {
        super("상품을 찾을 수 없습니다: " + productCode);
    }
}
