package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.in.RequeueFailedLedgerOutboxUseCase;
import github.lms.lemuel.ledger.application.port.out.LoadLedgerOutboxPort;
import github.lms.lemuel.ledger.application.port.out.SaveLedgerOutboxPort;
import github.lms.lemuel.ledger.domain.LedgerOutboxTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 원장 아웃박스 FAILED 항목 운영 서비스 — 조회·일괄 재큐.
 *
 * <p>재큐는 어댑터 UPDATE 트랜잭션(FAILED → PENDING, retry_count 리셋)에 위임한다. 되돌린 뒤에는
 * 기존 {@code LedgerOutboxPoller} 가 정상 경로로 재처리하며, 처리 로직 자체가 멱등이라 재큐가
 * 이중 분개를 만들지 않는다.
 */
@Service
public class LedgerOutboxAdminService implements RequeueFailedLedgerOutboxUseCase {

    private static final Logger log = LoggerFactory.getLogger(LedgerOutboxAdminService.class);

    private final LoadLedgerOutboxPort loadPort;
    private final SaveLedgerOutboxPort savePort;

    public LedgerOutboxAdminService(LoadLedgerOutboxPort loadPort, SaveLedgerOutboxPort savePort) {
        this.loadPort = loadPort;
        this.savePort = savePort;
    }

    @Override
    public List<LedgerOutboxTask> listFailed(int limit) {
        return loadPort.findFailed(limit);
    }

    @Override
    public long countFailed() {
        return loadPort.countFailed();
    }

    @Override
    public int requeueFailed(int limit) {
        int requeued = savePort.requeueFailed(limit);
        if (requeued > 0) {
            log.warn("[LedgerOutbox] FAILED 항목 재큐: requeued={}", requeued);
        }
        return requeued;
    }
}
