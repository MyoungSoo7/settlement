package github.lms.lemuel.settlement.application.port.out;

import github.lms.lemuel.settlement.domain.SettlementAdjustment;

public interface SaveSettlementAdjustmentPort {
    SettlementAdjustment save(SettlementAdjustment adjustment);
}
