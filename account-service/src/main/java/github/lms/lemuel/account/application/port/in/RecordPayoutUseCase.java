package github.lms.lemuel.account.application.port.in;

import java.math.BigDecimal;

/**
 * 셀러 실지급(payout.completed) 을 GL 에 전기하는 인바운드 포트 (Kafka 컨슈머가 호출).
 *
 * <p>단순 {@code payoutCompleted}(DR SELLER_PAYABLE / CR CASH) 전기가 아니라, 현재 SELLER_PAYABLE 잔액을
 * 기준으로 차변을 분할해 잔액을 초과하는 실지급분을 회수채권으로 라우팅한다(감사 MED-3 봉합 — 대응
 * 크레딧이 없는 수동 송금이 통제계정을 음수로 모는 것을 방지).
 */
public interface RecordPayoutUseCase {

    void recordPayout(String sellerId, String payoutId, BigDecimal amount);
}
