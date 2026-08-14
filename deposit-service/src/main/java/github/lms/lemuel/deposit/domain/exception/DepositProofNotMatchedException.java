package github.lms.lemuel.deposit.domain.exception;

import github.lms.lemuel.deposit.domain.DepositProofStatus;

/**
 * 예치금 증빙 대사 게이트 위반 — 해당 참조에 증빙이 첨부돼 있는데 대사를 통과하지 못한 채
 * 수기 기표를 시도했다. 웹 어댑터가 422({@code DEPOSIT_PROOF_NOT_MATCHED})로 매핑한다 —
 * 요청 형식의 잘못이 아니라 "지금은 기표 불가"(잔고 부족 422 와 같은 결).
 */
public class DepositProofNotMatchedException extends RuntimeException {

    public DepositProofNotMatchedException(String referenceId, DepositProofStatus status, String note) {
        super("예치금 증빙 대사 미통과(" + status + ")로 기표할 수 없습니다: referenceId=" + referenceId
                + (note == null ? "" : " — " + note));
    }

    /** 전면 강제(required=true)에서 증빙 미첨부 수기 기표 시도 (면제 referenceType 은 대상 아님). */
    public static DepositProofNotMatchedException missing(String referenceId) {
        return new DepositProofNotMatchedException(
                "증빙이 첨부되지 않아 기표할 수 없습니다(전면 강제): referenceId=" + referenceId);
    }

    private DepositProofNotMatchedException(String message) {
        super(message);
    }
}
