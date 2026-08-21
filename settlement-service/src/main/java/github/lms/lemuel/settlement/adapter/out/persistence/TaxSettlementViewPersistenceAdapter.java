package github.lms.lemuel.settlement.adapter.out.persistence;

import github.lms.lemuel.settlement.application.port.out.LoadSellerIdPort;
import github.lms.lemuel.tax.application.dto.TaxSettlementView;
import github.lms.lemuel.tax.application.port.out.LoadSettlementForTaxPort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * tax 가 선언한 {@link LoadSettlementForTaxPort} 를 settlement 슬라이스가 구현한다 —
 * 정산 1건을 세무용 {@link TaxSettlementView} 로 넘긴다
 * ({@link SettlementForLedgerPersistenceAdapter} 와 동형).
 *
 * <p>{@link LoadSellerIdPort}(payment.order_id → orders.product_id → products.seller_id)로 실제 소유
 * 셀러를 해석해 {@code TaxSettlementView.sellerId} 를 채운다 — {@code TaxContextResolver} 가 이 필드로
 * 요청 sellerId 의 소유권을 대조한다(2026-07-24, ADR 0029 후속 IDOR 수정).
 *
 * <p>이 클래스가 <b>settlement 쪽에 사는 이유</b>: 이전에는 tax 의 어댑터가 settlement 의 JPA
 * 리포지토리·엔티티를 직접 읽어 tax→settlement 방향 의존을 만들었고, 그것이 순환
 * {@code settlement → tax → settlement} 의 한쪽 다리였다. 포트를 거치게 하는 것만으로는 방향이
 * 그대로라 순환이 끊기지 않는다 — 인터페이스는 필요한 쪽(tax)이 소유하고 <b>구현은 데이터를 가진
 * 쪽(settlement)이 제공</b>해야 뒤집힌다.
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

    /**
     * {@code Settlement.getImmediatePayoutAmount()} 와 동일 규칙(holdbackReleased ? net : max(net−holdback,0)).
     *
     * <p>도메인 메서드를 직접 부르지 않고 엔티티에서 다시 계산하는 것은 의도된 잔여 중복이다.
     * 도메인 경로는 {@code Settlement.rehydrate} 를 거치는데, 그 경로는 {@code Money.of(holdbackAmount)} 와
     * {@code SettlementStatus.fromString(status)} 에서 <b>레거시 행(holdback null·미등록 상태값)에 더 엄격</b>하다.
     * 여기로 옮겨오면서 조용히 갈아끼우면 세무 조회가 과거 행에서 터질 수 있어, 규칙 통합은 그 관용도
     * 차이를 덮는 테스트를 갖춘 뒤 별도로 다룬다.
     */
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
