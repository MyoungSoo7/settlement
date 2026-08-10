package github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol;

/**
 * 전문 인코딩/디코딩 규격 위반 — 길이 초과, 숫자 필드 비숫자, 전문 총길이 불일치 등.
 *
 * <p>어댑터 계층 예외다. {@code FepFirmBankingAdapter} 가 잡아
 * {@code FirmBankingPort.FirmBankingException} 으로 변환해 포트 계약을 지킨다.
 */
public class FepProtocolException extends RuntimeException {

    public FepProtocolException(String message) {
        super(message);
    }

    public FepProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
