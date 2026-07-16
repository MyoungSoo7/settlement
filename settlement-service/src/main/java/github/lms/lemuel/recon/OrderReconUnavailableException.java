package github.lms.lemuel.recon;

/**
 * order 내부 대사 API({@code /internal/recon/*}) 호출이 연결/응답 타임아웃 또는 5xx 로 실패했음을 나타낸다.
 *
 * <p>대사(reconciliation)는 배치/관리 작업이라 order 일시 장애 시 <b>해당 대사 run 만 명시적으로 실패</b>하면
 * 되고, 정산 생성·조회 핫패스는 이벤트 기반이라 영향받지 않는다. {@link OrderReconClient} 가 원본
 * {@code RestClientException}(타임아웃·5xx)을 이 타입으로 번역해 던지므로, 호출 측(대사 run)은 무한 hang
 * 이나 불투명한 500 대신 이 신호를 받아 run 을 SKIP/실패 처리할 수 있다.
 */
public class OrderReconUnavailableException extends RuntimeException {

    public OrderReconUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
