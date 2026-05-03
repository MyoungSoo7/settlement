package github.lms.lemuel.settlement.application.port.in;

import java.time.LocalDate;

/**
 * 일일 정산 확정 UseCase (Inbound Port)
 */
public interface ConfirmDailySettlementsUseCase {

    ConfirmSettlementResult confirmDailySettlements(ConfirmSettlementCommand command);

    record ConfirmSettlementCommand(LocalDate targetDate) {
        public ConfirmSettlementCommand {
            if (targetDate == null) {
                throw new IllegalArgumentException("Target date is required");
            }
        }
    }

    record ConfirmSettlementResult(int confirmedCount, int totalSettlements) {}
}
