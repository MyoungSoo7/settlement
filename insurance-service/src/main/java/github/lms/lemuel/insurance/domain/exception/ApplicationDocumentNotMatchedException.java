package github.lms.lemuel.insurance.domain.exception;

import github.lms.lemuel.insurance.domain.ApplicationDocumentStatus;

/**
 * 청약서류 대사 게이트 위반 — 첨부된 서류가 MATCHED 가 아닌데 승인을 시도했다.
 *
 * <p>완전판매 게이트(교부 증빙 409)와 달리 <b>422</b> 로 매핑한다 — 요청 형식의 잘못이 아니라
 * "지금은 승인 불가"이며, 409 는 이미 전이 충돌·미교부가 쓰고 있어 구분을 유지한다.
 */
public class ApplicationDocumentNotMatchedException extends RuntimeException {

    public ApplicationDocumentNotMatchedException(String applicationId,
                                                  ApplicationDocumentStatus status, String note) {
        super("청약서류 대사 미통과(" + status + ")로 승인할 수 없습니다: applicationId=" + applicationId
                + (note == null ? "" : " — " + note));
    }
}
