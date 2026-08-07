package github.lms.lemuel.insurance.application.port.out;

import github.lms.lemuel.insurance.application.port.out.LoadInsuranceProductPort.ProductSnapshot;
import github.lms.lemuel.insurance.domain.DisclosureDelivery;

/**
 * 상품설명서 교부 이력 저장 포트 — INSERT 전용 (append-only).
 *
 * <p>교부 시점 상품 조건 스냅샷을 함께 고정한다 — 이후 상품 개정이 있어도
 * "그때 무엇을 설명했는지"가 재현된다.
 */
public interface SaveDisclosureDeliveryPort {

    DisclosureDelivery save(DisclosureDelivery delivery, ProductSnapshot productAtDelivery);
}
