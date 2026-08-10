package github.lms.lemuel.insurance.application.port.out;

/**
 * 상품설명서 교부 이력 조회 포트 — 완전판매 게이트의 근거.
 */
public interface LoadDisclosureDeliveryPort {

    /** 해당 청약에 교부 증빙이 1건 이상 존재하는가. */
    boolean existsForApplication(String applicationId);
}
