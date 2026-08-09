package github.lms.lemuel.insurance.domain.exception;

/** 다른 설계사의 가입설계 접근 시도 — 소유권 대조 실패(IDOR 차단, 403). */
public class ProposalOwnershipException extends RuntimeException {

    public ProposalOwnershipException(String proposalId) {
        super("본인이 작성한 가입설계만 접근할 수 있습니다: " + proposalId);
    }
}
