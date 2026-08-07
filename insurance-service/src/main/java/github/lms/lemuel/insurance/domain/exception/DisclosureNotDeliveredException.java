package github.lms.lemuel.insurance.domain.exception;

/**
 * 완전판매 게이트 위반 — 상품설명서 교부 증빙 없이 청약을 승인하려는 시도.
 *
 * <p>승인 전 반드시 해당 청약의 교부 기록(disclosure_deliveries)이 있어야 한다.
 * 웹 어댑터가 409 로 매핑한다 (교부 후 재시도 가능).
 */
public class DisclosureNotDeliveredException extends RuntimeException {

    public DisclosureNotDeliveredException(String applicationId) {
        super("상품설명서 교부 증빙 없이 승인할 수 없습니다 (완전판매 게이트): applicationId=" + applicationId);
    }
}
