package github.lms.lemuel.payout.application.service;

import github.lms.lemuel.common.opssignal.OpsSignalCategory;
import github.lms.lemuel.common.opssignal.OpsSignalPort;
import github.lms.lemuel.payout.application.port.out.FirmBankingPort;
import github.lms.lemuel.payout.application.port.out.PublishPayoutEventPort;
import github.lms.lemuel.payout.application.port.out.SavePayoutPort;
import github.lms.lemuel.payout.domain.Payout;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Payout 집행의 짧은 트랜잭션 단계들 — 각 메서드가 독립 {@code REQUIRES_NEW} 로 <b>즉시 커밋</b>한다.
 *
 * <p><b>왜 별도 빈인가:</b> {@link PayoutSingleExecutor} 오케스트레이터는 이 단계들 <i>사이</i>에
 * (트랜잭션 밖에서) 펌뱅킹 {@code send()} 를 호출한다. 스프링 트랜잭션 경계는 프록시로만 열리므로
 * self-invocation(같은 빈 내부 호출)으로는 새 트랜잭션이 생기지 않는다. 따라서 트랜잭션 단계를
 * 오케스트레이터와 분리된 빈으로 두어, 각 단계가 프록시를 지나 독립적으로 begin·commit 되게 한다.
 *
 * <p>이 구조가 만드는 성질: 펌뱅킹 왕복 동안 DB 커넥션·행 잠금을 점유하지 않는다(phase1 이 이미 커밋됨).
 */
@Service
public class PayoutTxSteps {

    private final SavePayoutPort savePort;
    private final OpsSignalPort opsSignalPort;
    private final PublishPayoutEventPort publishPayoutEventPort;

    public PayoutTxSteps(SavePayoutPort savePort, OpsSignalPort opsSignalPort,
                         PublishPayoutEventPort publishPayoutEventPort) {
        this.savePort = savePort;
        this.opsSignalPort = opsSignalPort;
        this.publishPayoutEventPort = publishPayoutEventPort;
    }

    /**
     * Phase 1 — 원자적 선점(REQUESTED → SENDING)을 짧은 독립 트랜잭션으로 <b>즉시 커밋</b>한다.
     *
     * <p>{@code claimForSending} 은 {@code WHERE status = REQUESTED} UPDATE 라 동시에 두 인스턴스가
     * 같은 건을 잡아도 단 하나만 1행을 갱신한다. 진 쪽은 빈 결과를 받아 {@link PayoutConcurrentClaimException}
     * 으로 빠져 펌뱅킹을 호출하지 않는다(이중 송금 차단). 커밋으로 SENDING 이 확정되며 행 잠금이 즉시
     * 풀려, 이어질 펌뱅킹 왕복이 커넥션을 물지 않는다.
     *
     * @return SENDING 으로 선점된 Payout(이 트랜잭션 커밋 후의 스냅샷)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payout claim(Long payoutId) {
        return savePort.claimForSending(payoutId)
                .orElseThrow(() -> new PayoutConcurrentClaimException(payoutId));
    }

    /**
     * Phase 3(성공) — SENDING → COMPLETED 를 별도 짧은 트랜잭션으로 확정한다.
     *
     * <p>{@code save} 는 id 로 현재 행(SENDING)을 다시 읽어 도메인 상태를 반영하므로, phase1 과 다른
     * 트랜잭션에서 호출돼도 무결하다.
     *
     * <p><b>GL 현금 폐루프(ADR 0026 Option A):</b> COMPLETED 확정과 <i>같은 트랜잭션</i>에서
     * {@code lemuel.payout.completed} 를 Outbox 에 기록한다 — account-service 가 소비해
     * DR SELLER_PAYABLE / CR CASH 로 미지급금을 상계하고 현금 유출을 GL 에 반영한다. 상태 저장과 이벤트
     * 저장이 한 트랜잭션에 묶여야(원자성) 지급 완료됐는데 GL 에 누락되는 부정합이 생기지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(Payout sending, String firmBankingTransactionId) {
        sending.markCompleted(firmBankingTransactionId);
        savePort.save(sending);
        publishPayoutEventPort.publishPayoutCompleted(
                sending.getId(), sending.getSettlementId(), sending.getSellerId(), sending.getAmount());
    }

    /**
     * Phase 3(실패) — SENDING → FAILED 를 별도 짧은 트랜잭션으로 확정하고 운영 관제 실패 신호를 emit 한다.
     *
     * <p>이 트랜잭션은 예외 없이 정상 종료되어 FAILED 가 durable 하게 커밋된다. 재던짐은 호출자가 이
     * 커밋 <i>이후</i> 수행하므로, 예전 {@code noRollbackFor} 로 지키던 "실패 기록은 남기되 자동 재송금은
     * 차단" 을 트랜잭션 경계 자체로 보장한다(FAILED 는 REQUESTED 배치 대상이 아니므로 운영자 retry 로만 재시도).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Payout sending, FirmBankingPort.FirmBankingException e) {
        sending.markFailed(e.getErrorCode() + " " + e.getMessage());
        savePort.save(sending);
        // 운영 관제 실패 신호 — best-effort(절대 throw 안 함), 정산금 지급 실패를 operation-service 로 집계.
        opsSignalPort.emit(OpsSignalCategory.SETTLEMENT_FAILED, "payout", String.valueOf(sending.getId()),
                Map.of("reason", "FIRM_BANKING", "errorCode", String.valueOf(e.getErrorCode())));
    }
}
