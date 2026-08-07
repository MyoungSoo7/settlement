package github.lms.lemuel.insurance.application.port.out;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 상품 카탈로그 조회 포트 — 상품설명서 렌더링 입력.
 */
public interface LoadInsuranceProductPort {

    Optional<ProductSnapshot> findByCode(String productCode);

    /**
     * 상품설명서 렌더링에 필요한 상품 조건 스냅샷.
     *
     * @param insurerCode 원수사 코드 (V6 — 미지정 상품은 null)
     */
    record ProductSnapshot(String productCode, String productName, String productType,
                           BigDecimal annualPremium, BigDecimal coverageAmount,
                           String insurerCode, boolean active) {
    }
}
