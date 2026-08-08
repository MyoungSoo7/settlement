package github.lms.lemuel.deposit.application.port.in;

import github.lms.lemuel.deposit.domain.DepositHold;
import github.lms.lemuel.deposit.domain.DepositHolderType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 예치금 선점(Hold) 유스케이스.
 * card.authorized 이벤트 소비 시 호출된다.
 */
public interface PlaceHoldUseCase {

    /**
     * 동일 (holderType, holderReference) 재요청은 멱등 처리(기존 hold 반환).
     *
     * @return 생성된(또는 기존) hold
     */
    DepositHold placeHold(Long sellerId, DepositHolderType holderType,
                           String holderReference, BigDecimal amount,
                           LocalDateTime expiresAt);
}
