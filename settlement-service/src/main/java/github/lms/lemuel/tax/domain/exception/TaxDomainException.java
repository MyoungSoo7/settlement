package github.lms.lemuel.tax.domain.exception;

/**
 * 세무 도메인 예외 최상위 — 부가세·원천징수·세금계산서 불변식 위반의 타입 루트.
 *
 * <p>금융 5서비스 도메인은 generic {@link IllegalArgumentException} 대신 타입 도메인 예외를 던진다
 * (OO 게이트). 세무 도메인의 모든 규칙 위반은 이 계열로 표현한다.
 */
public abstract class TaxDomainException extends RuntimeException {

    protected TaxDomainException(String message) {
        super(message);
    }
}
