package github.lms.lemuel.loan.application.port.in;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * settlement 의 SettlementCreated 이벤트를 받아 로컬 정산 뷰에 적재하는 인바운드 포트.
 */
public interface IngestSettlementUseCase {

    void ingest(IngestSettlementCommand command);

    record IngestSettlementCommand(Long settlementId, Long sellerId, BigDecimal amount, LocalDate dueDate) {
    }
}
