package github.lms.lemuel.recovery.application.port.out;

import java.math.BigDecimal;

/**
 * 지급후 회수 채권(SellerRecovery) 도메인 이벤트를 account-service 로 발행하는 아웃바운드 포트
 * (Transactional Outbox 경유, ADR 0026 Option ①).
 *
 * <p>Outbox 폴러가 aggregateType="seller_recovery" + eventType 으로 토픽을 라우팅한다. aggregate 세그먼트는
 * 소문자화만 되고 snake 변환되지 않으므로(KafkaOutboxPublisher.resolveTopic), lemuel.seller_recovery.* 를
 * 얻으려면 aggregateType 이 반드시 리터럴 "seller_recovery" 여야 한다:
 * <ul>
 *   <li>Opened → lemuel.seller_recovery.opened (account: DR SELLER_RECOVERY_RECEIVABLE / CR CASH)</li>
 *   <li>Offset → lemuel.seller_recovery.offset (account: DR SELLER_PAYABLE / CR SELLER_RECOVERY_RECEIVABLE)</li>
 * </ul>
 * amount 는 settlement 계열 규약대로 JSON number 로 직렬화된다.
 */
public interface PublishSellerRecoveryEventPort {

    /** 지급 완료 후 회수가 필요한 채권이 발생(OPEN)할 때. */
    void publishRecoveryOpened(long recoveryId, long sellerId, BigDecimal amount);

    /**
     * 채권이 이후 정산금과 상계될 때.
     * @param recoveryId 상계된 채권 — 미상 시 {@code null}(payload 에서 생략, 계약상 optional).
     */
    void publishRecoveryOffset(long allocationId, Long recoveryId, long sellerId, BigDecimal amount);
}
