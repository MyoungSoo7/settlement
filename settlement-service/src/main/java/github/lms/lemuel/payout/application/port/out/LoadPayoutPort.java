package github.lms.lemuel.payout.application.port.out;

import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutStatus;
import github.lms.lemuel.payout.domain.PayoutType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LoadPayoutPort {

    Optional<Payout> findById(Long id);

    /**
     * 같은 정산은 1번만 송금되도록 멱등 체크.
     */
    Optional<Payout> findBySettlementId(Long settlementId);

    /**
     * (정산, 지급유형) 멱등 체크 — 유형별로 최대 1건만 생성되게 한다.
     */
    Optional<Payout> findBySettlementIdAndType(Long settlementId, PayoutType payoutType);

    /**
     * 배치 처리용 — REQUESTED 상태 페이지 조회.
     */
    List<Payout> findByStatus(PayoutStatus status, int limit);

    /**
     * 셀러별 일자 송금 합계 — 한도 검증용.
     */
    BigDecimal sumCompletedBySellerOn(Long sellerId, LocalDate date);

    /**
     * 시스템 전체 일자 송금 합계 — 일별 운영 한도 검증용.
     */
    BigDecimal sumCompletedSystemwideOn(LocalDate date);
}
