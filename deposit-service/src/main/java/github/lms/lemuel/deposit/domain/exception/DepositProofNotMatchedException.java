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
}
