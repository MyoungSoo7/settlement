package github.lms.lemuel.insurance.domain.exception;

/** 다른 설계사의 가입설계 접근 시도 — 소유권 대조 실패(IDOR 차단, 403). */
public class ProposalOwnershipException extends RuntimeException {

    public ProposalOwnershipException(String proposalId) {
        super("본인이 작성한 가입설계만 접근할 수 있습니다: " + proposalId);
    }

    private ProposalOwnershipException() {
        super("본인 확인이 불가한 토큰입니다 — 재로그인 후 다시 시도하세요");
    }

    /**
     * 요청자를 특정할 수 없는 경우(userId 가 없는 구 토큰·미인증).
     *
     * <p>소유권 대조와 같은 403 으로 응답한다 — 식별 불가와 타인 자원 접근을 구분해 알려주면
     * 설계 존재 여부가 새 나간다.
     */
    public static ProposalOwnershipException unidentifiedRequester() {
        return new ProposalOwnershipException();
    }
}
