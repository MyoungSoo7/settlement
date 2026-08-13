package github.lms.lemuel.deposit.application.port.out;

import github.lms.lemuel.deposit.domain.DepositHold;
import github.lms.lemuel.deposit.domain.DepositHolderType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface LoadDepositHoldPort {
    Optional<DepositHold> findByHolderTypeAndReference(DepositHolderType holderType, String holderReference);

    /**
     * 만료 시각이 지난 ACTIVE hold 조회 — 만료 회수 배치를 위해 준비된 포트.
     *
     * <p><b>프로덕션 호출자가 아직 없다.</b> 전용 부분 인덱스(ACTIVE + expires_at)와 어댑터 구현까지
     * 있지만 이것을 도는 {@code @Scheduled} 는 없고, {@code DepositServiceApplication} 도
     * {@code @EnableScheduling} 을 켜지 않았다. 그 결과 <b>만료된 hold 가 locked 를 계속 잡고 있어
     * 가용액이 조용히 줄어든 채 유지된다</b> — 잔고가 틀리는 게 아니라 덜 보이는 방향이라 알람이 울리지 않는다.
     *
     * <p>배선 시 유의: 다중 인스턴스에서 중복 회수가 나지 않게 ShedLock(shared-common 제공)을 건다.
     */
    List<DepositHold> findActiveExpiredBefore(LocalDateTime cutoff);
}
