package github.lms.lemuel.deposit.domain.exception;

/**
 * 예치금 증빙 미존재 — 웹 어댑터가 404({@code DEPOSIT_PROOF_NOT_FOUND})로 매핑한다.
 *
 * <p>{@code IllegalStateException} 을 상속하지 않는 이유: {@code DepositExceptionHandler} 의
 * ISE 매핑(404 catch-all)에 섞이면 메시지·코드가 계좌 미존재와 구분되지 않는다 — 전용 핸들러로 간다.
 */
public class DepositProofNotFoundException extends RuntimeException {

    public DepositProofNotFoundException(Long proofId) {
        super("예치금 증빙을 찾을 수 없습니다: proofId=" + proofId);
    }
}
