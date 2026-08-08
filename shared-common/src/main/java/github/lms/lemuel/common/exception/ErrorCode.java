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
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "이 리소스에 접근할 권한이 없습니다."),
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
    IMAGE_STORAGE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이미지 저장에 실패했습니다. 잠시 후 다시 시도해주세요."),

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
    LEDGER_NOT_FOUND(HttpStatus.NOT_FOUND, "원장 항목을 찾을 수 없습니다."),
    LEDGER_PERIOD_CLOSED(HttpStatus.CONFLICT, "마감된 원장 기간에는 신규 분개를 작성할 수 없습니다."),
    LEDGER_PERIOD_IMBALANCE(HttpStatus.UNPROCESSABLE_ENTITY, "시산표 차대가 균형을 이루지 않아 기간을 마감할 수 없습니다."),
    MONTHLY_CLOSING_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 월의 정보계 마감 이력이 없습니다."),
    MONTHLY_CLOSING_LOCKED(HttpStatus.CONFLICT, "원장 마감된 기간의 정보계 마트는 재적재할 수 없습니다."),
    MONTHLY_CLOSING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "정보계 월마감 실행에 실패했습니다."),

    // ─── loan (선정산·기업 신용대출) ─────────────────────────────────────────────
    CORPORATE_LOAN_NOT_FOUND(HttpStatus.NOT_FOUND, "대출 건 또는 재무자료를 찾을 수 없습니다."),
    CORPORATE_LOAN_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY, "대출 심사가 거절되었습니다."),

    // ─── loan (담보·개인신용 대출) ───────────────────────────────────────────────
    SECURED_LOAN_NOT_FOUND(HttpStatus.NOT_FOUND, "담보/개인신용 대출을 찾을 수 없습니다."),
    SECURED_LOAN_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY, "담보/개인신용 대출 심사가 거절되었습니다."),

    // ─── investment (CEO 투자하기) ──────────────────────────────────────────────
    INVESTMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "투자 주문을 찾을 수 없습니다."),
    NOT_INVESTABLE(HttpStatus.UNPROCESSABLE_ENTITY, "투자 부적격 종목입니다."),
    INSUFFICIENT_FUNDING(HttpStatus.UNPROCESSABLE_ENTITY, "가용 재원이 부족합니다."),

    // ─── account (계정계 GL) ────────────────────────────────────────────────────
    NON_POSITIVE_ENTRY_AMOUNT(HttpStatus.BAD_REQUEST, "전표 금액은 양수여야 합니다."),
    UNBALANCED_ACCOUNT_ENTRY(HttpStatus.BAD_REQUEST, "차변과 대변 계정은 달라야 합니다."),
    ENTRY_AMOUNT_SCALE_EXCEEDED(HttpStatus.BAD_REQUEST, "전표 금액의 소수 자릿수가 허용 범위(2)를 초과했습니다."),

    // ─── card (법인카드) ───
    CARD_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "카드계정을 찾을 수 없습니다."),
    CARD_NOT_FOUND(HttpStatus.NOT_FOUND, "카드를 찾을 수 없습니다."),
    CARD_ACCOUNT_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 카드계정이 존재하는 조직입니다."),
    CARD_ALREADY_ISSUED(HttpStatus.CONFLICT, "이미 활성 카드를 보유한 임직원입니다."),
    CARD_SCREENING_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY, "카드 심사 기준을 충족하지 못했습니다."),
    CARD_SUB_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "임직원 한도 합계가 법인 마스터 한도를 초과합니다."),
    CARD_HOLDER_NOT_MEMBER(HttpStatus.UNPROCESSABLE_ENTITY, "해당 조직의 활성 구성원이 아닙니다."),
    CARD_FORBIDDEN(HttpStatus.FORBIDDEN, "이 작업을 수행할 권한이 없습니다."),
    CARD_AUTHORIZATION_NOT_FOUND(HttpStatus.NOT_FOUND, "승인 홀드를 찾을 수 없습니다."),
    // 재원 조회 실패는 폴백 없이 명시적 실패시킨다 — 재원을 모른 채 추정 한도를 주면 그 자체가 여신 사고다.
    CARD_FUNDING_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "재원 조회에 실패했습니다. 잠시 후 다시 시도해주세요.");

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
