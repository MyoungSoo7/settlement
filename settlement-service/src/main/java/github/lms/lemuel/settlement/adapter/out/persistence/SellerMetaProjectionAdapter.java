package github.lms.lemuel.settlement.adapter.out.persistence;

import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementPaymentViewRepository;
import github.lms.lemuel.settlement.application.port.out.LoadSellerIdPort;
import github.lms.lemuel.settlement.application.port.out.LoadSellerSettlementCyclePort;
import github.lms.lemuel.settlement.application.port.out.LoadSellerTierPort;
import github.lms.lemuel.settlement.domain.SellerTier;
import github.lms.lemuel.settlement.domain.SettlementCycle;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 셀러 메타(ID·등급·주기) 해석 어댑터 (ADR 0020 Phase 3b-6).
 *
 * <p>이전(SellerTierJdbcAdapter)에는 opslab 의 payments→orders→products→users 4테이블 native 조인으로
 * 해석했으나, 읽기 컷오버 후에는 settlement 소유 프로젝션(settlement_payment_view)만 조회한다.
 * 프로젝션의 seller_id/seller_tier/settlement_cycle 은 PaymentCaptured 이벤트 동봉값으로 적재된다
 * (Phase 1). order DB 직접 조회 0.
 *
 * <p>주의: 정산 생성 컨슈머는 payment_view upsert 보다 정산 생성을 먼저 수행하므로, 동일 트랜잭션의
 * 생성 fallback 에서는 프로젝션이 비어 empty 를 반환할 수 있다. 그러나 이 fallback 은 메타 미동봉(구) 이벤트
 * 에서만 호출되며(정상 경로는 이벤트 동봉값 사용), 배치 확정 경로에서는 프로젝션이 이미 적재돼 있어 정상 동작한다.
 */
@Repository
public class SellerMetaProjectionAdapter
        implements LoadSellerTierPort, LoadSellerSettlementCyclePort, LoadSellerIdPort {

    private final SettlementPaymentViewRepository paymentViewRepository;

    public SellerMetaProjectionAdapter(SettlementPaymentViewRepository paymentViewRepository) {
        this.paymentViewRepository = paymentViewRepository;
    }

    @Override
    public Optional<SellerTier> findTierByPaymentId(Long paymentId) {
        return paymentViewRepository.findById(paymentId)
                .map(v -> v.getSellerTier())
                .filter(t -> t != null && !t.isBlank())
                .map(SellerTier::fromStringOrDefault);
    }

    @Override
    public Optional<SettlementCycle> findCycleByPaymentId(Long paymentId) {
        return paymentViewRepository.findById(paymentId)
                .map(v -> v.getSettlementCycle())
                .filter(c -> c != null && !c.isBlank())
                .map(SettlementCycle::fromStringOrDefault);
    }

    @Override
    public Optional<Long> findSellerIdByPaymentId(Long paymentId) {
        return paymentViewRepository.findById(paymentId)
                .map(v -> v.getSellerId());
    }
}
