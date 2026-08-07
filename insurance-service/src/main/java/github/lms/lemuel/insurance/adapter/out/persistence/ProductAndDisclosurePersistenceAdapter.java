package github.lms.lemuel.insurance.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.insurance.application.port.out.LoadBancaSalesPort;
import github.lms.lemuel.insurance.application.port.out.LoadInsuranceProductPort;
import github.lms.lemuel.insurance.application.port.out.SaveDisclosureDeliveryPort;
import github.lms.lemuel.insurance.domain.BancaRuleEvaluator.BankInsurerPremium;
import github.lms.lemuel.insurance.domain.DisclosureDelivery;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 상품 카탈로그 조회 + 교부 이력 저장 + 방카 집계 — ③ 상품설명서·25%룰 영속 어댑터.
 */
@Component
public class ProductAndDisclosurePersistenceAdapter
        implements LoadInsuranceProductPort, SaveDisclosureDeliveryPort,
        github.lms.lemuel.insurance.application.port.out.LoadDisclosureDeliveryPort, LoadBancaSalesPort {

    private final SpringDataInsuranceProductRepository productRepository;
    private final SpringDataDisclosureDeliveryRepository deliveryRepository;
    private final SpringDataPolicyRepository policyRepository;
    private final ObjectMapper objectMapper;

    public ProductAndDisclosurePersistenceAdapter(
            SpringDataInsuranceProductRepository productRepository,
            SpringDataDisclosureDeliveryRepository deliveryRepository,
            SpringDataPolicyRepository policyRepository,
            @Qualifier("outboxObjectMapper") ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.deliveryRepository = deliveryRepository;
        this.policyRepository = policyRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ProductSnapshot> findByCode(String productCode) {
        return productRepository.findByProductCode(productCode)
                .map(InsuranceProductJpaEntity::toSnapshot);
    }

    @Override
    public DisclosureDelivery save(DisclosureDelivery delivery, ProductSnapshot productAtDelivery) {
        return deliveryRepository.saveAndFlush(
                        DisclosureDeliveryJpaEntity.fromDomain(delivery, snapshotJson(productAtDelivery)))
                .toDomain();
    }

    @Override
    public boolean existsForApplication(String applicationId) {
        return deliveryRepository.existsByApplicationId(java.util.UUID.fromString(applicationId));
    }

    @Override
    public List<BankInsurerPremium> aggregateBancaPremiums(LocalDate fromInclusive, LocalDate toExclusive) {
        return policyRepository.aggregateBancaPremiumsByBankAndInsurer(fromInclusive, toExclusive);
    }

    /** 교부 시점 상품 조건을 JSONB 스냅샷으로 고정한다 — 금액은 plain string (N5). */
    private String snapshotJson(ProductSnapshot p) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("productCode",    p.productCode());
        snapshot.put("productName",    p.productName());
        snapshot.put("productType",    p.productType());
        snapshot.put("annualPremium",  p.annualPremium().toPlainString());
        snapshot.put("coverageAmount", p.coverageAmount().toPlainString());
        if (p.insurerCode() != null) {
            snapshot.put("insurerCode", p.insurerCode());
        }
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("상품 스냅샷 직렬화 실패", e);
        }
    }
}
