package github.lms.lemuel.payout.application.port.out;

import github.lms.lemuel.payout.domain.SellerBankAccount;

import java.math.BigDecimal;

/**
 * 펌뱅킹 (기업 인터넷뱅킹) 송금 어댑터 포트.
 *
 * <p>실 운영: KB / 신한 / NH 펌뱅킹 API 또는 토스페이먼츠 송금 API. 본 포트폴리오는
 * mock 어댑터로 응답 시뮬레이션. 도메인은 PG와 동일하게 회복탄력성 패턴 (Resilience4j) 으로 보호.
 */
public interface FirmBankingPort {

    /**
     * 송금 요청. 동기 응답으로 거래 ID 반환 (실 운영은 비동기 콜백 패턴 추가 검토 필요).
     *
     * @return 펌뱅킹 측 거래 ID (사후 추적·환수 근거)
     * @throws FirmBankingException 송금 실패 시
     */
    String send(SellerBankAccount account, BigDecimal amount, String referenceId)
            throws FirmBankingException;

    /**
     * 펌뱅킹 호출 실패 시 던지는 예외 — 송금 실패 사유 보존.
     */
    class FirmBankingException extends RuntimeException {
        private final String errorCode;

        public FirmBankingException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public FirmBankingException(String errorCode, String message, Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode;
        }

        public String getErrorCode() { return errorCode; }
    }
}
