package github.lms.lemuel.deposit.application.port.out;

import github.lms.lemuel.deposit.domain.DepositHold;
import github.lms.lemuel.deposit.domain.DepositHolderType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface LoadDepositHoldPort {
    Optional<DepositHold> findByHolderTypeAndReference(DepositHolderType holderType, String holderReference);

    /**
     * 만료 시각이 지났는데 <b>아직 재원을 잡고 있는</b> hold 조회 — 만료 회수 배치의 입력.
     *
     * <p>"ACTIVE" 가 아니라 "still holding" 인 것이 요점이다. 재원을 잡는 상태는 ACTIVE 와
     * PARTIALLY_CAPTURED 둘이며({@code DepositHold.isActive()} 도 둘을 함께 본다), ACTIVE 만
     * 보던 이전 형태는 부분 매입 후 방치된 hold 의 잔여를 영구히 잠갔다. 부분 캡처는 예외가
     * 아니라 카드 매입의 정상 경로라 그 누락은 시간이 갈수록 쌓인다.
     *
     * <p>부분 인덱스 {@code idx_deposit_holds_unsettled_expiring}(V20260813120000)가 같은 두
     * 상태를 덮는다 — 대상 상태를 바꾸면 인덱스 술어도 함께 바꿔야 한다.
     */
    List<DepositHold> findExpiredStillHolding(LocalDateTime cutoff);
}
