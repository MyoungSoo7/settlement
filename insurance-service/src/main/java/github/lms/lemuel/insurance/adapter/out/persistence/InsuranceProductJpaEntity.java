package github.lms.lemuel.insurance.adapter.out.persistence;

import github.lms.lemuel.insurance.application.port.out.LoadInsuranceProductPort.ProductSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * insurance_products 테이블 매핑 (V1 + V6 insurer_code) — 상품설명서 렌더링 입력 조회 전용.
 */
@Entity
@Table(name = "insurance_products", schema = "opslab")
public class InsuranceProductJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_code", nullable = false, length = 32)
    private String productCode;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "product_type", nullable = false, length = 50)
    private String productType;

    @Column(name = "annual_premium", nullable = false, precision = 19, scale = 2)
    private BigDecimal annualPremium;

    @Column(name = "coverage_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal coverageAmount;

    @Column(name = "first_year_commission_rate", nullable = false, precision = 7, scale = 6)
    private BigDecimal firstYearCommissionRate;

    @Column(name = "insurer_code", length = 32)
    private String insurerCode;

    @Column(nullable = false)
    private boolean active;

    protected InsuranceProductJpaEntity() {
    }

    public ProductSnapshot toSnapshot() {
        return new ProductSnapshot(productCode, productName, productType,
                annualPremium, coverageAmount, firstYearCommissionRate, insurerCode, active);
    }

    public String getProductCode() {
        return productCode;
    }
}
