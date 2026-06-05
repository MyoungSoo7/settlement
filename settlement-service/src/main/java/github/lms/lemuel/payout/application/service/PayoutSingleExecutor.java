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
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void execute(Payout payout) {
        payout.startSending();
        Payout sending = savePort.save(payout);

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
