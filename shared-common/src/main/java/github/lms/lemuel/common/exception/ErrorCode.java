package github.lms.lemuel.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 플랫폼 공통 에러 코드 카탈로그.
 *
 * <p>각 코드는 (HTTP 상태, 기본 메시지)를 보유한다. {@link BusinessException} 이 이 코드를 들고 던져지면
 * {@code GlobalExceptionHandler} 의 단일 핸들러가 코드→상태/응답으로 변환한다. 새로운 도메인 예외는
 * 여기에 코드만 추가하고 {@code BusinessException} 을 상속하면 되며 별도의 @ExceptionHandler 가 필요 없다.
 *
 * <p>이 enum 은 {@code common.exception}(인프라) 패키지에 있어 HttpStatus 를 참조해도 무방하다.
 * 도메인 예외는 이 코드(enum 상수)만 참조하므로 Spring 에 직접 의존하지 않는다(헥사고날 도메인 순수성 유지).
 */
public enum ErrorCode {

    // ─── 공통(기술) ──────────────────────────────────────────────────────────
    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    INVALID_STATE(HttpStatus.BAD_REQUEST, "현재 상태에서 처리할 수 없습니다."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "요청 파라미터가 올바르지 않습니다."),
    LOCK_TIMEOUT(HttpStatus.CONFLICT, "요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해주세요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요."),

    // ─── order ───────────────────────────────────────────────────────────────
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    USER_NOT_EXISTS(HttpStatus.BAD_REQUEST, "존재하지 않는 사용자입니다."),
    DUPLICATE_ORDER_SUBMISSION(HttpStatus.CONFLICT, "이미 처리 중이거나 처리된 주문 요청입니다."),

    // ─── user ────────────────────────────────────────────────────────────────
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 존재하는 이메일입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_PASSWORD_RESET_TOKEN(HttpStatus.BAD_REQUEST, "유효하지 않거나 만료된 비밀번호 재설정 토큰입니다."),

    // ─── product ─────────────────────────────────────────────────────────────
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다."),
    DUPLICATE_PRODUCT_NAME(HttpStatus.CONFLICT, "이미 존재하는 상품명입니다."),
    INSUFFICIENT_STOCK(HttpStatus.BAD_REQUEST, "재고가 부족합니다."),
    STOCK_CONCURRENCY(HttpStatus.CONFLICT, "재고 동시성 충돌이 발생했습니다. 잠시 후 다시 시도해주세요."),

    // ─── category ────────────────────────────────────────────────────────────
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "카테고리를 찾을 수 없습니다."),
    DUPLICATE_SLUG(HttpStatus.CONFLICT, "이미 존재하는 슬러그입니다."),
    CIRCULAR_REFERENCE(HttpStatus.BAD_REQUEST, "순환 참조가 발생합니다."),
    CATEGORY_HAS_PRODUCTS(HttpStatus.CONFLICT, "연결된 상품이 있어 삭제할 수 없습니다."),
    CATEGORY_HAS_CHILDREN(HttpStatus.CONFLICT, "하위 카테고리가 있어 삭제할 수 없습니다."),
    CATEGORY_DEPTH_EXCEEDED(HttpStatus.BAD_REQUEST, "허용된 카테고리 깊이를 초과했습니다."),

    // ─── payment ─────────────────────────────────────────────────────────────
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다."),
    INVALID_PAYMENT_STATE(HttpStatus.BAD_REQUEST, "잘못된 결제 상태입니다."),
    INVALID_ORDER_STATE(HttpStatus.BAD_REQUEST, "잘못된 주문 상태입니다."),
    MISSING_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "멱등성 키(Idempotency-Key)가 필요합니다."),
    REFUND_EXCEEDS_PAYMENT(HttpStatus.CONFLICT, "환불 금액이 결제 금액을 초과합니다."),
    REFUND_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "환불 처리 중 오류가 발생했습니다."),

    // ─── settlement / ledger ──────────────────────────────────────────────────
    SETTLEMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "정산을 찾을 수 없습니다."),
    LEDGER_NOT_FOUND(HttpStatus.NOT_FOUND, "원장 항목을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    /** 응답 본문의 {@code errorCode} 값 — enum 이름을 그대로 사용한다. */
    public String code() {
        return name();
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
