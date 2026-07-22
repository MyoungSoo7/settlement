package github.lms.lemuel.recovery.application.port.out;

import github.lms.lemuel.recovery.domain.RecoveryAllocation;

import java.math.BigDecimal;
import java.util.List;

public interface RecoveryAllocationPort {

    RecoveryAllocation save(RecoveryAllocation allocation);

    /**
     * 확정 재실행 멱등 — 해당 후속 정산의 기존 상계 총액(없으면 0). 0 초과면 이전 실행이 이미
     * 상계를 마친 것이므로 새 상계 없이 그 값을 그대로 지급 차감액으로 재사용한다.
     */
    BigDecimal sumBySettlementId(Long settlementId);

    /** 조회 API — 셀러의 상계 이력(최신순, 채권 join). LoadSellerRecoveryPort.findBySellerId 와 반환형이 달라 별도 명명. */
    List<RecoveryAllocation> findAllocationsBySellerId(Long sellerId);
}
