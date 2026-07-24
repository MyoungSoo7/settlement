package github.lms.lemuel.tax.adapter.out.persistence;

import github.lms.lemuel.settlement.adapter.out.persistence.SettlementJpaEntity;
import github.lms.lemuel.settlement.adapter.out.persistence.SpringDataSettlementJpaRepository;
import github.lms.lemuel.settlement.application.port.out.LoadSellerIdPort;
import github.lms.lemuel.tax.application.dto.TaxSettlementView;
import github.lms.lemuel.tax.application.port.out.LoadSettlementForTaxPort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * settlement_jpa_entity 를 어댑터 레벨에서만 read 하여 세무 도메인에 {@link TaxSettlementView} 로 전달한다
 * (SettlementForLedgerPersistenceAdapter 와 동형 — 세무 application 은 settlement 엔티티를 import 하지 않는다).
 *
 * <p>{@link LoadSellerIdPort}(payment.order_id → orders.product_id → products.seller_id, settlement 도메인의
 * 기존 출력 포트)로 실제 소유 셀러를 함께 해석해 {@code TaxSettlementView.sellerId} 를 채운다 — 세무 application
 * 은 여전히 settlement 엔티티를 import 하지 않지만, 어댑터 레벨에서 settlement 출력 포트를 재사용하는 것은
 * 기존 확립 패턴(SettlementConfirmItemWriter 등)과 동형이며 MSA 경계(cross-service)와는 무관하다(2026-07-24,
 * ADR 0029 후속 IDOR 수정 — TaxContextResolver 가 이 필드로 요청 sellerId 소유권을 대조한다).
 */
@Component
public class TaxSettlementViewPersistenceAdapter implements LoadSettlementForTaxPort {

    private final SpringDataSettlementJpaRepository settlementRepository;
    private final LoadSellerIdPort loadSellerIdPort;

    public TaxSettlementViewPersistenceAdapter(SpringDataSettlementJpaRepository settlementRepository,
                                               LoadSellerIdPort loadSellerIdPort) {
        this.settlementRepository = settlementRepository;
        this.loadSellerIdPort = loadSellerIdPort;
    }

    @Override
    public Optional<TaxSettlementView> findById(Long settlementId) {
        return settlementRepository.findById(settlementId).map(this::toView);
    }

    private TaxSettlementView toView(SettlementJpaEntity e) {
        Long sellerId = loadSellerIdPort.findSellerIdByPaymentId(e.getPaymentId()).orElse(null);
        return new TaxSettlementView(e.getId(), e.getCommission(), e.getNetAmount(),
                e.getSettlementDate(), e.getStatus(), immediatePayoutAmount(e), sellerId);
    }

    /** Settlement.getImmediatePayoutAmount() 와 동일 규칙(holdbackReleased ? net : max(net−holdback,0)). */
    private static BigDecimal immediatePayoutAmount(SettlementJpaEntity e) {
        if (e.getNetAmount() == null) {
            return BigDecimal.ZERO;
        }
        if (e.isHoldbackReleased()) {
            return e.getNetAmount();
        }
        BigDecimal holdback = e.getHoldbackAmount() != null ? e.getHoldbackAmount() : BigDecimal.ZERO;
        BigDecimal immediate = e.getNetAmount().subtract(holdback);
        return immediate.max(BigDecimal.ZERO);
    }
}
