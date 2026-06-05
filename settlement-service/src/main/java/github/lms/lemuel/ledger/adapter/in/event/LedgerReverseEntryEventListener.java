package github.lms.lemuel.ledger.adapter.in.event;

import github.lms.lemuel.ledger.adapter.in.event.dto.LedgerReverseEntryEvent;
import github.lms.lemuel.ledger.application.port.in.ReverseEntryUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 환불 정산조정이 commit 된 후 ledger 역분개를 작성한다.
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} 으로 settlement_adjustments
 * INSERT 가 실제로 커밋된 뒤에만 동작 — 정산 트랜잭션 롤백과의 정합성 어긋남 방지.
 *
 * <p>실패해도 환불 자체는 이미 commit 됐으니 외부에 전파하지 않고 로그만 남긴다.
 * (재처리는 후속 보강 배치 또는 관리자 수동 트리거에서)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LedgerReverseEntryEventListener {

    private final ReverseEntryUseCase reverseEntryUseCase;

    @Async("ledgerTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(LedgerReverseEntryEvent event) {
        if (event == null) return;
        try {
            reverseEntryUseCase.reverseForRefund(
                    event.settlementId(),
                    event.refundId(),
                    event.refundAmount(),
                    event.adjustmentDate());
        } catch (RuntimeException e) {
            log.error("Ledger reverse entry creation failed: settlementId={}, refundId={}, refundAmount={}, error={}",
                    event.settlementId(), event.refundId(), event.refundAmount(), e.getMessage(), e);
        }
    }
}
