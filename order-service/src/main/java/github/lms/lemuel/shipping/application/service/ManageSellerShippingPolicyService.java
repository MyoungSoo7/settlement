package github.lms.lemuel.shipping.application.service;

import github.lms.lemuel.shipping.application.port.in.ManageSellerShippingPolicyUseCase;
import github.lms.lemuel.shipping.application.port.out.LoadSellerShippingPolicyPort;
import github.lms.lemuel.shipping.application.port.out.SaveSellerShippingPolicyPort;
import github.lms.lemuel.shipping.domain.SellerShippingPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class ManageSellerShippingPolicyService implements ManageSellerShippingPolicyUseCase {

    private static final Logger log = LoggerFactory.getLogger(ManageSellerShippingPolicyService.class);

    private final LoadSellerShippingPolicyPort loadPort;
    private final SaveSellerShippingPolicyPort savePort;

    public ManageSellerShippingPolicyService(LoadSellerShippingPolicyPort loadPort,
                                             SaveSellerShippingPolicyPort savePort) {
        this.loadPort = loadPort;
        this.savePort = savePort;
    }

    @Override
    public SellerShippingPolicy upsert(Long sellerId, BigDecimal baseFee, BigDecimal freeThreshold) {
        // 값 검증은 도메인 팩토리가 한다 — 음수 배송비·음수 임계는 여기 도달 전에 거절된다.
        SellerShippingPolicy policy = SellerShippingPolicy.of(sellerId, baseFee, freeThreshold);
        SellerShippingPolicy saved = savePort.save(policy);
        log.info("셀러 배송비 정책 저장: sellerId={}, baseFee={}, freeThreshold={}",
                sellerId, baseFee, freeThreshold);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SellerShippingPolicy> find(Long sellerId) {
        return loadPort.loadBySellerId(sellerId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SellerShippingPolicy> findAll() {
        return loadPort.loadAll();
    }
}
