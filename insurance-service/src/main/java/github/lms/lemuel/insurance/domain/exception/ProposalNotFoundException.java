package github.lms.lemuel.insurance.domain.exception;

/** 존재하지 않는 가입설계 조회. */
public class ProposalNotFoundException extends RuntimeException {

    public ProposalNotFoundException(String proposalId) {
        super("가입설계를 찾을 수 없습니다: " + proposalId);
    }
}
