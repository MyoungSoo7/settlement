package github.lms.lemuel.ledger.adapter.in.batch;

import github.lms.lemuel.ledger.application.port.in.ProcessLedgerOutboxPort;
import github.lms.lemuel.ledger.domain.LedgerOutboxTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 원장 아웃박스 로컬 폴러.
 *
 * <p>PENDING 작업을 주기적으로 읽어 멱등 원장 use case 를 직접 호출한다 — Kafka 미경유,
 * {@code app.kafka.enabled} 와 무관하게 동일 동작. 정산/환불 트랜잭션과 같은 커밋으로 적재되므로
 * 크래시 후에도 작업이 살아남아 결국 처리된다(at-least-once + 멱등).
 *
 * <p>처리와 마킹을 분리: {@code execute} (use case 자체 트랜잭션) 성공 시 {@code markDone},
 * 실패 시 {@code markFailed} 를 각각 별도 트랜잭션으로 호출 — 한 건 실패가 배치 전체를 막지 않는다.
 *
 * <p>{@code app.ledger-outbox.enabled=false} 로 비활성 가능(통합 테스트에서 수동 구동 시 사용).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ledger-outbox.enabled", havingValue = "true", matchIfMissing = true)
public class LedgerOutboxPoller {

    private static final int BATCH_SIZE = 100;

    private final ProcessLedgerOutboxPort processPort;

    @Scheduled(fixedDelayString = "${app.ledger-outbox.poll-delay-ms:5000}")
    @SchedulerLock(name = "ledger-outbox-poller", lockAtMostFor = "PT5M")
    public void poll() {
        List<LedgerOutboxTask> batch = processPort.fetchPending(BATCH_SIZE);
        if (batch.isEmpty()) return;

        log.info("Ledger outbox 처리 시작: pending={}", batch.size());
        int done = 0;
        int failed = 0;
        for (LedgerOutboxTask task : batch) {
            try {
                processPort.execute(task);
                processPort.markDone(task.id());
                done++;
            } catch (RuntimeException e) {
                log.error("Ledger outbox 처리 실패: id={}, type={}, settlementId={}, retryCount={}, error={}",
                        task.id(), task.type(), task.settlementId(), task.retryCount(), e.getMessage(), e);
                processPort.markFailed(task.id(), e.getMessage());
                failed++;
            }
        }
        log.info("Ledger outbox 처리 완료: done={}, failed={}", done, failed);
    }
}
