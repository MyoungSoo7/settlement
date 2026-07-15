package github.lms.lemuel.investment.application.exception;

import java.math.BigDecimal;

/**
 * 가용 재원(available) 부족으로 주문/집행 불가 → HTTP 422.
 *
 * <p>요청액과 가용 재원을 메시지 문자열에만 묻어두지 않고 {@link #getRequested()}/{@link #getAvailable()}
 * 로 구조화 보존한다(둘 다 nullable — 문자열 생성자 사용 시).
 */
public class InsufficientFundingException extends RuntimeException {

    private final BigDecimal requested;
    private final BigDecimal available;

    public InsufficientFundingException(String message) {
        this(message, null, null);
    }

    public InsufficientFundingException(String message, BigDecimal requested, BigDecimal available) {
        super(message);
        this.requested = requested;
        this.available = available;
    }

    /** 주문/집행하려 한 금액. */
    public BigDecimal getRequested() {
        return requested;
    }

    /** 시점상 가용 재원(부족의 상한). */
    public BigDecimal getAvailable() {
        return available;
    }
}
