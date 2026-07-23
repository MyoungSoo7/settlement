package github.lms.lemuel.payout.application.port.out;

import java.math.BigDecimal;

/**
 * Payout 도메인 이벤트를 외부(account-service)로 발행하는 아웃바운드 포트 (Transactional Outbox 경유).
 *
 * <p>account-service 는 소비 전용이라 payout 현금 유출을 스스로 발행할 수 없다 — settlement 가 발행 몫을
 * 진다(ADR 0026 Option A). Outbox 폴러가 aggregateType="Payout" + eventType="PayoutCompleted" 로
 * 토픽을 자동 라우팅한다: PayoutCompleted → {@code lemuel.payout.completed}
 * (account: DR SELLER_PAYABLE / CR CASH — 미지급금 상계 + 현금 유출).
 *
 * <p>발행 호출은 지급 완료 상태 저장과 <b>같은 트랜잭션</b>(PayoutTxSteps.markCompleted 의 REQUIRES_NEW)
 * 안에서 이뤄져야 Outbox 원자성이 보장된다 — 비트랜잭션 오케스트레이터에 넣으면 상태·이벤트가 갈라진다.
 */
public interface PublishPayoutEventPort {

    /**
     * 셀러 정산금 실지급 완료를 발행한다.
     *
     * @param payoutId     지급 식별자(전표 자연키의 refId)
     * @param settlementId 정산 식별자(수동 송금 시 null)
     * @param sellerId     셀러 식별자(GL owner)
     * @param amount       지급 금액(양수)
     */
    void publishPayoutCompleted(long payoutId, Long settlementId, long sellerId, BigDecimal amount);
}
