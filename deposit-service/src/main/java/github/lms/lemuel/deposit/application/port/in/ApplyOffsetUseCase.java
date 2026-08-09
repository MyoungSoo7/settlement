package github.lms.lemuel.deposit.application.port.in;

import github.lms.lemuel.deposit.domain.DepositHolderType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 예치금 상계(Offset) 유스케이스.
 * card.captured 이벤트 소비 시 호출된다.
 *
 * <p>혼합 모델(C): offset 은 선행 hold 를 필수로 요구하지 않는다.
 * hold 가 있으면 locked 에서 먼저 상계하고 잔여분은 release.
 * hold 가 없거나 EXPIRED/VOIDED 면 available 에서 직접 상계.
 * 잔고 부족 시 부분 상계 후 shortfall 레코드를 영속화한다.
 */
public interface ApplyOffsetUseCase {

    /**
     * @param sellerId        셀러 ID
     * @param holderType      홀더 유형 (카드 승인 등)
     * @param holderReference 홀더 참조 ID (authorizationId 등)
     * @param offsetAmount    상계 요청 금액
     * @param offsetSequence  상계 순서 (멱등 키 구성 요소)
     * @param occurredAt      사건 발생 시각 (shortfall 이벤트 payload 에 포함)
     */
    void applyOffset(Long sellerId, DepositHolderType holderType,
                     String holderReference, BigDecimal offsetAmount,
                     int offsetSequence, OffsetDateTime occurredAt);
}
