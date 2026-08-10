package github.lms.lemuel.insurance.domain.exception;

/**
 * 존재하지 않는 청약 ID 로 언더라이팅을 시도한 경우. 웹 어댑터가 404 로 매핑한다.
 */
public class ApplicationNotFoundException extends RuntimeException {

    public ApplicationNotFoundException(String applicationId) {
        super("청약을 찾을 수 없습니다: " + applicationId);
    }
}
