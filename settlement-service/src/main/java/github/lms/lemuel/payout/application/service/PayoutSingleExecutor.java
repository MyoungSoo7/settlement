package github.lms.lemuel.payout.application.service;

import github.lms.lemuel.payout.application.port.out.FirmBankingPort;
import github.lms.lemuel.payout.application.port.out.SavePayoutPort;
import github.lms.lemuel.payout.domain.Payout;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 단일 Payout 실행 트랜잭션 경계.
 */
@Service
public class PayoutSingleExecutor {

    private final SavePayoutPort savePort;
    private final FirmBankingPort firmBanking;

    public PayoutSingleExecutor(SavePayoutPort savePort, FirmBankingPort firmBanking) {
        this.savePort = savePort;
        this.firmBanking = firmBanking;
    }

    /**
     * 개별 Payout 실행. REQUIRES_NEW 트랜잭션으로 격리해 한 건 실패가 다른 건에 영향 없게 한다.
     *
     * <p>외부 송금 *직전* 에 {@code claimForSending} 으로 REQUESTED → SENDING 선점을 원자적으로
     * 확정한다. 다른 인스턴스가 이미 선점했다면 {@link PayoutConcurrentClaimException} 으로 빠져
     * 펌뱅킹을 호출하지 않는다 — 롤링 배포 중첩 구간의 이중 송금을 원천 차단한다.
     *
     * <p><b>실패 처리 — {@code noRollbackFor}:</b> 펌뱅킹 실패 시 SENDING → FAILED 로 표기하고
     * 사유를 영속화한 뒤 예외를 다시 던진다. {@code noRollbackFor} 가 없으면 이 throw 가
     * REQUIRES_NEW 트랜잭션을 통째로 롤백해 (1) FAILED 기록이 사라지고 (2) SENDING 선점까지
     * 되돌아가 REQUESTED 로 복귀 → 다음 배치가 같은 건을 재송금한다. 펌뱅킹이 타임아웃으로
     * 던졌지만 은행에선 실제 이체된 경우 *이중 송금* 으로 이어진다. 따라서 펌뱅킹 예외는
     * 롤백 대상에서 제외하고 FAILED 를 커밋해, 자동 재시도가 아닌 운영자 {@code retry} 로만
     * 다시 시도되게 한다 (referenceId 로 펌뱅킹 측 멱등 추적 가능).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = FirmBankingPort.FirmBankingException.class)
    public void execute(Payout payout) {
        Payout sending = savePort.claimForSending(payout.getId())
                .orElseThrow(() -> new PayoutConcurrentClaimException(payout.getId()));

        try {
            String referenceId = "PAYOUT-" + sending.getId();
            String txnId = firmBanking.send(sending.getAccount(), sending.getAmount(), referenceId);
            sending.markCompleted(txnId);
        } catch (FirmBankingPort.FirmBankingException e) {
            sending.markFailed(e.getErrorCode() + " " + e.getMessage());
            savePort.save(sending);
            throw e;
        }
        savePort.save(sending);
    }
}
