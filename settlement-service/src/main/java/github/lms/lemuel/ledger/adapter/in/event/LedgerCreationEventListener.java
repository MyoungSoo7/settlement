package github.lms.lemuel.ledger.adapter.in.event;

import github.lms.lemuel.ledger.application.port.in.CreateLedgerEntryUseCase;
import github.lms.lemuel.settlement.adapter.in.event.dto.SettlementIndexEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Settlement DONE(BATCH_CONFIRMED) 이벤트를 구독해 원장 분개를 작성한다.
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} 으로 정산 확정 트랜잭션이
 * 실제로 커밋된 후에만 분개가 작성되어, 정산 롤백과의 정합성 어긋남을 방지한다.
 *
 * <p>실패 시 다음 정산 처리에 영향이 가지 않도록 개별 settlementId 단위로 잡는다
 * ({@link CreateLedgerEntryUseCase#createFromSettlements} 안에서 처리).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LedgerCreationEventListener {

    private final CreateLedgerEntryUseCase createLedgerEntryUseCase;

    @Async("ledgerTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSettlementConfirmed(SettlementIndexEvent event) {
        if (event == null || event.getEventType() != SettlementIndexEvent.IndexEventType.BATCH_CONFIRMED) {
            return;
        }

        log.info("Ledger 분개 생성 트리거: BATCH_CONFIRMED, settlementIds.size={}",
                event.getSettlementIds().size());

        createLedgerEntryUseCase.createFromSettlements(event.getSettlementIds());
    }
}
