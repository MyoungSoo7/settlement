package github.lms.lemuel.deposit.domain.exception;

/**
 * 예치금 증빙 OCR 판독 실패·미구성 — 무폴백(ADR 0036): 부분 결과·추정 판독을 만들지 않고
 * 503({@code DEPOSIT_PROOF_OCR_UNAVAILABLE})으로 끊는다. 추정 판독을 원장 기표 근거로 쓰는 순간
 * 조용한 오대사다.
 */
public class DepositProofOcrUnavailableException extends RuntimeException {

    public DepositProofOcrUnavailableException(String message) {
        super(message);
    }
}
