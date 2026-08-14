package github.lms.lemuel.insurance.domain.exception;

/**
 * 판매채널 불변식 위반 — BANCA 채널인데 판매 은행이 없거나, FC 채널인데 은행이 지정된 경우.
 *
 * <p>V6 의 {@code chk_policy_banca_bank} CHECK 제약과 동일 불변식을 도메인이 먼저 강제한다.
 */
public class InvalidSalesChannelException extends RuntimeException {

    public InvalidSalesChannelException(String message) {
        super(message);
    }
}
