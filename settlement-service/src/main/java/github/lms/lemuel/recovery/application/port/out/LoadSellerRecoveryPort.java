package github.lms.lemuel.recovery.application.port.out;

import github.lms.lemuel.recovery.domain.SellerRecovery;

import java.util.List;
import java.util.Optional;

public interface LoadSellerRecoveryPort {

    /** 발생 멱등 축 — 조정 1건당 채권 1건. */
    Optional<SellerRecovery> findBySourceAdjustmentId(Long sourceAdjustmentId);

    /** 상계 스캔 — 셀러의 OPEN 채권을 오래된 순(id 오름차순)으로 비관락 로드. */
    List<SellerRecovery> findOpenBySellerIdForUpdate(Long sellerId);

    /** 조회 API — 셀러의 채권 전체(최신순). */
    List<SellerRecovery> findBySellerId(Long sellerId);
}
