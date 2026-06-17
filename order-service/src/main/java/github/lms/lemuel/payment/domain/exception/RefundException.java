package github.lms.lemuel.payment.domain.exception;

/**
 * 환불 처리 도메인 예외 (payment 도메인 소유).
 *
 * <p>이전에는 shared-common 에 있었으나, 환불은 결제 도메인 개념이고 order-service 만 사용하므로
 * payment 도메인으로 이전했다 (다른 서비스로의 도메인 누수 제거 — MSA/DDD 경계 정합).
 */
public class RefundException extends RuntimeException {
    public RefundException(String message) {
        super(message);
    }

    public RefundException(String message, Throwable cause) {
        super(message, cause);
    }
}
