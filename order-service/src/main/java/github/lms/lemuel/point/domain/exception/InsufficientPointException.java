package github.lms.lemuel.point.domain.exception;

import java.math.BigDecimal;

/**
 * 포인트 잔액 부족 (도메인 계층 — Spring 의존 0).
 *
 * <p>비즈니스 정상 결과다 — 결제 경로에서는 이 예외가 곧 "포인트로는 결제할 수 없다"는 답이며,
 * 컨트롤러가 422 로 번역한다. 재시도 대상이 아니다.
 */
public class InsufficientPointException extends RuntimeException {

    private final BigDecimal requested;
    private final BigDecimal available;

    public InsufficientPointException(String message, BigDecimal requested, BigDecimal available) {
        super(message);
        this.requested = requested;
        this.available = available;
    }

    public BigDecimal getRequested() { return requested; }
    public BigDecimal getAvailable() { return available; }
}
