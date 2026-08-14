package github.lms.lemuel.insurance.domain.exception;

/** 다른 접수자의 청약 접근 시도 — 소유권 대조 실패(IDOR 차단, 403). */
public class ApplicationOwnershipException extends RuntimeException {

    public ApplicationOwnershipException(String applicationId) {
        super("본인이 접수한 청약만 접근할 수 있습니다: " + applicationId);
    }

    private ApplicationOwnershipException() {
        super("본인 확인이 불가한 토큰입니다 — 재로그인 후 다시 시도하세요");
    }

    /**
     * 요청자를 특정할 수 없는 경우(userId 가 없는 구 토큰·미인증).
     *
     * <p>소유권 대조와 같은 403 으로 응답한다 — 식별 불가와 타인 자원 접근을 구분해 알려주면
     * 청약 존재 여부가 새 나간다(가입설계·계약 경로와 동일 관례).
     */
    public static ApplicationOwnershipException unidentifiedRequester() {
        return new ApplicationOwnershipException();
    }
}
