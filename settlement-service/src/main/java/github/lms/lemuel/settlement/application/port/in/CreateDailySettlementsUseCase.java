package github.lms.lemuel.settlement.application.port.in;

import java.time.LocalDate;

public interface CreateDailySettlementsUseCase {

    CreateSettlementResult createDailySettlements(CreateSettlementCommand command);

    record CreateSettlementCommand(
            LocalDate targetDate
    ) {
        public CreateSettlementCommand {
            if (targetDate == null) {
                throw new IllegalArgumentException("Target date cannot be null");
            }
        }
    }

    record CreateSettlementResult(
            LocalDate targetDate,
            int totalPayments,
            int createdCount
    ) {}
}
