package github.lms.lemuel.insurance.domain.exception;

/**
 * 청약서류 OCR 판독 실패·미구성 — 무폴백(ADR 0036): 부분 결과·추정 판독을 만들지 않고 끊는다.
 * 웹 어댑터가 503 으로 매핑한다 (재시도 가능).
 */
public class ApplicationDocumentOcrUnavailableException extends RuntimeException {

    public ApplicationDocumentOcrUnavailableException(String message) {
        super(message);
    }
}
